package com.smartwalker.presentation.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartwalker.BuildConfig
import com.smartwalker.domain.model.*
import com.smartwalker.data.remote.NavigationApi
import com.smartwalker.domain.usecase.RerouteUseCase
import com.smartwalker.domain.usecase.SearchDestinationUseCase
import com.smartwalker.domain.usecase.StartNavigationUseCase
import com.smartwalker.service.location.LocationFusionService
import com.smartwalker.service.location.RouteDeviationDetector
import com.smartwalker.service.voice.NavigationCommand
import com.smartwalker.service.voice.NavigationGuidanceService
import com.smartwalker.service.voice.ObstacleAlertService
import com.smartwalker.service.voice.VoiceInputService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val startNavigationUseCase: StartNavigationUseCase,
    private val rerouteUseCase: RerouteUseCase,
    private val searchDestinationUseCase: SearchDestinationUseCase,
    private val locationFusionService: LocationFusionService,
    private val routeDeviationDetector: RouteDeviationDetector,
    private val voiceInputService: VoiceInputService,
    private val navigationGuidanceService: NavigationGuidanceService,
    private val obstacleAlertService: ObstacleAlertService,
    private val navigationApi: NavigationApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private var lastRerouteTime: Long? = null
    private var voiceInputJob: kotlinx.coroutines.Job? = null

    init {
        voiceInputService.initialize()
        observeLocation()
        observeDeviation()
        fetchInitialLocation()
    }

    private fun fetchInitialLocation() {
        viewModelScope.launch {
            // 검색 화면에서도 현재 위치 표시가 필요하므로 ViewModel 생성 직후 최초 시작.
            locationFusionService.startTracking()
            val initialPosition = locationFusionService.getCurrentPosition()
            if (initialPosition != null) {
                _uiState.update { it.copy(currentPosition = initialPosition) }
                Log.d(TAG, "Initial location: ${initialPosition.coordinate}")
            } else {
                Log.w(TAG, "Could not get initial location")
            }
        }
    }

    private fun observeLocation() {
        // UI 위치 표시: 업데이트마다 반영
        viewModelScope.launch {
            locationFusionService.fusedPosition
                .filterNotNull()
                .collect { position ->
                    _uiState.update { it.copy(currentPosition = position) }
                }
        }

        // 이탈/도착 감지: 500ms마다 한 번만 실행 (보행 속도에서 충분)
        viewModelScope.launch {
            locationFusionService.fusedPosition
                .filterNotNull()
                .sample(DEVIATION_CHECK_INTERVAL_MS)
                .collect { position ->
                    if (_uiState.value.isNavigating && _uiState.value.route != null) {
                        routeDeviationDetector.checkDeviation(position)
                        if (routeDeviationDetector.checkArrival(position)) {
                            handleArrival()
                        }
                    }
                }
        }

        // GPS 품질 모니터링: null 포함 수신해야 GPS 소실을 감지할 수 있음 (filterNotNull 사용 불가)
        viewModelScope.launch {
            locationFusionService.fusedPosition.collect { position ->
                val wasGpsLost = _uiState.value.isGpsLost
                val isNowGpsLost = position == null || !position.isAcceptable

                if (isNowGpsLost && !wasGpsLost && _uiState.value.isNavigating) {
                    navigationGuidanceService.announceGpsLost()
                } else if (!isNowGpsLost && wasGpsLost && _uiState.value.isNavigating) {
                    navigationGuidanceService.announceGpsRecovered()
                }

                _uiState.update { it.copy(isGpsLost = isNowGpsLost) }
            }
        }

        // TODO: 데모 후 삭제 - 원본 GPS 위치 별도 수집(디버그 표시용)
        viewModelScope.launch {
            locationFusionService.gpsPosition.collect { position ->
                _uiState.update { it.copy(gpsPosition = position) }
            }
        }
        // TODO: 데모 후 삭제 끝
    }

    private fun observeDeviation() {
        viewModelScope.launch {
            routeDeviationDetector.deviationState.collect { state ->
                when (state) {
                    is RouteDeviationDetector.DeviationState.Deviated -> {
                        val now = System.currentTimeMillis()
                        val cooldownOk = lastRerouteTime?.let { now - it > REROUTE_COOLDOWN_MS } ?: true
                        if (!_uiState.value.isRerouting && cooldownOk) {
                            handleDeviation()
                        }
                    }
                    is RouteDeviationDetector.DeviationState.Warning ->
                        Log.d(TAG, "Deviation warning: ${state.distanceMeters}m")
                    else -> {}
                }
            }
        }

        viewModelScope.launch {
            routeDeviationDetector.currentInstruction.collect { index ->
                val route = _uiState.value.route ?: return@collect
                val instruction = route.instructions.getOrNull(index) ?: return@collect
                _uiState.update { it.copy(currentInstruction = instruction) }
                announceInstruction(instruction)
            }
        }

        viewModelScope.launch {
            routeDeviationDetector.remainingDistanceMeters.collect { meters ->
                meters ?: return@collect
                _uiState.update { it.copy(remainingDistance = meters.toInt()) }
            }
        }
    }

    fun searchDestination(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }

            searchDestinationUseCase(query, _uiState.value.currentPosition?.coordinate)
                .onSuccess { results ->
                    _searchResults.value = results
                    _uiState.update { it.copy(isSearching = false) }
                }
                .onFailure { error ->
                    Log.e(TAG, "Search failed", error)
                    _uiState.update { it.copy(isSearching = false, error = "검색에 실패했습니다") }
                }
        }
    }

    fun startNavigation(destination: SearchResult) {
        viewModelScope.launch {
            val origin = _uiState.value.currentPosition?.coordinate
            if (origin == null) {
                // ② GPS 미확보: 권한이 방금 허용된 경우 ①이 아직 실행 전일 수 있으므로 재시도
                locationFusionService.startTracking()
                _uiState.update { it.copy(error = "GPS 신호를 기다리는 중입니다. 잠시 후 다시 시도해주세요.") }
                navigationGuidanceService.announceError("GPS 신호를 기다리고 있습니다")
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, destination = destination) }

            startNavigationUseCase(origin, destination.toCoordinate(), destination.name)
                .onSuccess { route ->
                    routeDeviationDetector.setRoute(route)
                    // ③ 실제 내비게이션 시작 (GPS + PDR + VO + IMU heading).
                    //    stopNavigation()/handleArrival() 이 stopTracking() 을 호출하므로 재시작 필요.
                    locationFusionService.startTracking()
                    obstacleAlertService.start(BuildConfig.GLASS_STREAM_URL)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isNavigating = true,
                            route = route,
                            currentInstruction = route.instructions.firstOrNull()
                        )
                    }

                    navigationGuidanceService.announceRouteStart(
                        route = route,
                        destinationName = destination.name,
                        currentLat = origin.latitude,
                        currentLng = origin.longitude
                    )

                    Log.d(TAG, "Navigation started to ${destination.name}")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to start navigation", error)
                    _uiState.update { it.copy(isLoading = false, error = "경로를 찾을 수 없습니다") }
                    navigationGuidanceService.announceError("경로를 찾을 수 없습니다")
                }
        }
    }

    fun stopNavigation() {
        routeDeviationDetector.clearRoute()
        locationFusionService.stopTracking()
        obstacleAlertService.stop()

        _uiState.update {
            it.copy(
                isNavigating = false,
                route = null,
                destination = null,
                currentInstruction = null
            )
        }

        navigationGuidanceService.announceNavigationStopped()
        Log.d(TAG, "Navigation stopped")
    }

    fun startVoiceInput() {
        voiceInputJob?.cancel()
        voiceInputJob = viewModelScope.launch {
            voiceInputService.listenForDestination().collect { result ->
                when (result) {
                    is VoiceInputService.VoiceInputResult.Recognized -> {
                        Log.d(TAG, "Voice input: ${result.text}")
                        _uiState.update { it.copy(searchQuery = result.text) }
                        searchDestination(result.text)
                    }
                    is VoiceInputService.VoiceInputResult.Partial -> {
                        _uiState.update { it.copy(searchQuery = result.text) }
                    }
                    is VoiceInputService.VoiceInputResult.Failed -> {
                        Log.e(TAG, "Voice input failed: ${result.reason}")
                    }
                }
            }
        }
    }

    fun startCommandListening() {
        viewModelScope.launch {
            voiceInputService.listenForNavigationCommand().collect { command ->
                handleNavigationCommand(command)
            }
        }
    }

    private fun handleNavigationCommand(command: NavigationCommand) {
        when (command) {
            is NavigationCommand.Repeat -> {
                Log.d(TAG, "Command: Repeat")
                repeatCurrentInstruction()
            }
            is NavigationCommand.Stop -> {
                Log.d(TAG, "Command: Stop")
                stopNavigation()
            }
            is NavigationCommand.RemainingDistance -> {
                Log.d(TAG, "Command: RemainingDistance")
                val remaining = _uiState.value.remainingDistance
                if (remaining != null) {
                    navigationGuidanceService.announceRemainingDistance(remaining)
                } else {
                    navigationGuidanceService.announceError("현재 경로 정보가 없습니다")
                }
            }
            is NavigationCommand.WhereAmI -> {
                Log.d(TAG, "Command: WhereAmI")
                announceCurrentLocation()
            }
            is NavigationCommand.Unknown -> {
                Log.d(TAG, "Command: Unknown ('${command.rawText}')")
                if (_uiState.value.isNavigating) {
                    navigationGuidanceService.announceError("명령을 이해하지 못했습니다")
                }
            }
        }
    }

    fun repeatCurrentInstruction() {
        val instruction = _uiState.value.currentInstruction ?: return
        val currentPos = _uiState.value.currentPosition
        navigationGuidanceService.repeatInstruction(
            instruction = instruction,
            route = _uiState.value.route,
            currentLat = currentPos?.coordinate?.latitude,
            currentLng = currentPos?.coordinate?.longitude
        )
    }

    private fun announceCurrentLocation() {
        val position = _uiState.value.currentPosition
        if (position == null) {
            navigationGuidanceService.announceError("현재 위치를 확인할 수 없습니다")
            return
        }
        viewModelScope.launch {
            runCatching {
                navigationApi.reverseGeocode(
                    lat = position.coordinate.latitude,
                    lng = position.coordinate.longitude
                )
            }.onSuccess { response ->
                navigationGuidanceService.announceWhereAmI(response.locationName)
            }.onFailure {
                Log.w(TAG, "Reverse geocode failed", it)
                navigationGuidanceService.announceError("현재 위치를 가져올 수 없습니다")
            }
        }
    }

    private suspend fun handleDeviation() {
        val route = _uiState.value.route ?: return
        val position = _uiState.value.currentPosition ?: return

        lastRerouteTime = System.currentTimeMillis()
        Log.d(TAG, "Handling route deviation")
        _uiState.update { it.copy(isRerouting = true) }
        navigationGuidanceService.announceRerouting()

        rerouteUseCase(route.sessionId, position.coordinate)
            .onSuccess { newRoute ->
                routeDeviationDetector.setRoute(newRoute)

                _uiState.update {
                    it.copy(
                        isRerouting = false,
                        route = newRoute,
                        currentInstruction = newRoute.instructions.firstOrNull()
                    )
                }

                navigationGuidanceService.announceNewRoute(
                    route = newRoute,
                    currentLat = position.coordinate.latitude,
                    currentLng = position.coordinate.longitude
                )

                Log.d(TAG, "Reroute successful")
            }
            .onFailure { error ->
                Log.e(TAG, "Reroute failed", error)
                _uiState.update { it.copy(isRerouting = false, error = "재탐색에 실패했습니다") }
                navigationGuidanceService.announceError("재탐색에 실패했습니다")
            }
    }

    private fun handleArrival() {
        navigationGuidanceService.announceArrival()

        _uiState.update {
            it.copy(
                isNavigating = false,
                hasArrived = true
            )
        }

        routeDeviationDetector.clearRoute()
        locationFusionService.stopTracking()
        obstacleAlertService.stop()

        Log.d(TAG, "Arrived at destination")
    }

    private fun announceInstruction(instruction: Instruction) {
        val currentPos = _uiState.value.currentPosition
        navigationGuidanceService.announceInstruction(
            instruction = instruction,
            route = _uiState.value.route,
            currentLat = currentPos?.coordinate?.latitude,
            currentLng = currentPos?.coordinate?.longitude
        )
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        voiceInputJob?.cancel()
        voiceInputService.stopListening()
        navigationGuidanceService.stop()
        locationFusionService.stopTracking()
        obstacleAlertService.stop()
    }

    companion object {
        private const val TAG = "NavigationViewModel"
        private const val REROUTE_COOLDOWN_MS = 15_000L
        private const val DEVIATION_CHECK_INTERVAL_MS = 500L
    }
}

data class NavigationUiState(
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val isNavigating: Boolean = false,
    val isRerouting: Boolean = false,
    val hasArrived: Boolean = false,
    val isGpsLost: Boolean = false,
    val currentPosition: FusedPosition? = null,
    val route: Route? = null,
    val destination: SearchResult? = null,
    val currentInstruction: Instruction? = null,
    val remainingDistance: Int? = null,
    val searchQuery: String = "",
    val error: String? = null,
    // TODO: 데모 후 삭제 - GPS 디버그 표시용
    val gpsPosition: FusedPosition? = null
    // TODO: 데모 후 삭제 끝
)
