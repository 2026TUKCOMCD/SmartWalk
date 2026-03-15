package com.navblind.presentation.navigation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navblind.BuildConfig
import com.navblind.domain.model.*
import com.navblind.domain.usecase.RerouteUseCase
import com.navblind.domain.usecase.SearchDestinationUseCase
import com.navblind.domain.usecase.StartNavigationUseCase
import com.navblind.service.location.LocationFusionService
import com.navblind.service.location.RouteDeviationDetector
import com.navblind.service.recording.DataCollectionService
import com.navblind.service.voice.NavigationCommand
import com.navblind.service.voice.NavigationGuidanceService
import com.navblind.service.voice.ObstacleAlertService
import com.navblind.service.voice.VoiceInputService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val startNavigationUseCase: StartNavigationUseCase,
    private val rerouteUseCase: RerouteUseCase,
    private val searchDestinationUseCase: SearchDestinationUseCase,
    private val locationFusionService: LocationFusionService,
    private val routeDeviationDetector: RouteDeviationDetector,
    private val voiceInputService: VoiceInputService,
    private val navigationGuidanceService: NavigationGuidanceService,
    private val obstacleAlertService: ObstacleAlertService
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    init {
        voiceInputService.initialize()
        observeLocation()
        observeDeviation()
        fetchInitialLocation()
    }

    private fun fetchInitialLocation() {
        viewModelScope.launch {
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

        // GPS 품질 모니터링 (T109): 신호 소실/복구 시 음성 안내
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

        // TODO: 데모 후 삭제 - GPS/VPS 위치 별도 수집
        viewModelScope.launch {
            locationFusionService.gpsPosition.collect { position ->
                _uiState.update { it.copy(gpsPosition = position) }
            }
        }
        viewModelScope.launch {
            locationFusionService.vpsPosition.collect { position ->
                _uiState.update { it.copy(vpsPosition = position) }
            }
        }
        // TODO: 데모 후 삭제 끝
    }

    // 재탐색 후 일정 시간 동안 재탐색 억제 (연속 호출 방지)
    private var lastRerouteTime = 0L

    private fun observeDeviation() {
        viewModelScope.launch {
            routeDeviationDetector.deviationState.collect { state ->
                when (state) {
                    is RouteDeviationDetector.DeviationState.Deviated -> {
                        val now = System.currentTimeMillis()
                        val cooldownOk = now - lastRerouteTime > REROUTE_COOLDOWN_MS
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
                if (index < route.instructions.size) {
                    val instruction = route.instructions[index]
                    _uiState.update { it.copy(currentInstructionIndex = index) }
                    announceInstruction(instruction)
                }
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
                // 위치 권한이 방금 허용되었을 수 있으므로 추적 재시도
                locationFusionService.startTracking()
                _uiState.update { it.copy(error = "GPS 신호를 기다리는 중입니다. 잠시 후 다시 시도해주세요.") }
                navigationGuidanceService.announceError("GPS 신호를 기다리고 있습니다")
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, destination = destination) }

            startNavigationUseCase(origin, destination.toCoordinate(), destination.name)
                .onSuccess { route ->
                    routeDeviationDetector.setRoute(route)
                    locationFusionService.startTracking()
                    obstacleAlertService.start(BuildConfig.GLASS_STREAM_URL)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isNavigating = true,
                            route = route,
                            currentInstructionIndex = 0
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
                currentInstructionIndex = 0
            )
        }

        navigationGuidanceService.announceNavigationStopped()
        Log.d(TAG, "Navigation stopped")
    }

    fun startVoiceInput() {
        viewModelScope.launch {
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

    /**
     * 음성 명령을 한 번 인식하고 처리합니다.
     * 네비게이션 중 버튼 탭 등으로 호출합니다.
     */
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
                repeatCurrentInstruction()
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
        val route = _uiState.value.route ?: return
        val index = _uiState.value.currentInstructionIndex
        val instruction = route.instructions.getOrNull(index) ?: return
        val currentPos = _uiState.value.currentPosition

        navigationGuidanceService.repeatInstruction(
            instruction = instruction,
            route = route,
            currentLat = currentPos?.coordinate?.latitude,
            currentLng = currentPos?.coordinate?.longitude
        )
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
                        currentInstructionIndex = 0
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
        val route = _uiState.value.route

        navigationGuidanceService.announceInstruction(
            instruction = instruction,
            route = route,
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

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            DataCollectionService.stop(context)
            _uiState.update { it.copy(isRecording = false) }
            Log.d(TAG, "Data collection stopped")
        } else {
            DataCollectionService.start(context, BuildConfig.GLASS_STREAM_URL)
            _uiState.update { it.copy(isRecording = true) }
            Log.d(TAG, "Data collection started (stream: ${BuildConfig.GLASS_STREAM_URL})")
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isRecording) {
            DataCollectionService.stop(context)
        }
        voiceInputService.stopListening()
        navigationGuidanceService.stop()
        locationFusionService.stopTracking()
        obstacleAlertService.stop()
    }

    companion object {
        private const val TAG = "NavigationViewModel"
        // 재탐색 후 이 시간(ms) 동안 추가 재탐색 억제
        private const val REROUTE_COOLDOWN_MS = 15_000L
        // 이탈/도착 감지 주기 (보행 속도 기준 500ms로 충분)
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
    val isRecording: Boolean = false,
    val currentPosition: FusedPosition? = null,
    val route: Route? = null,
    val destination: SearchResult? = null,
    val currentInstructionIndex: Int = 0,
    val searchQuery: String = "",
    val error: String? = null,
    // TODO: 데모 후 삭제 - GPS/VPS 디버그 표시용
    val gpsPosition: FusedPosition? = null,
    val vpsPosition: FusedPosition? = null
    // TODO: 데모 후 삭제 끝
) {
    val currentInstruction: Instruction?
        get() = route?.instructions?.getOrNull(currentInstructionIndex)

    val remainingDistance: Int?
        get() {
            val route = route ?: return null
            return route.instructions
                .drop(currentInstructionIndex)
                .sumOf { it.distance }
        }
}
