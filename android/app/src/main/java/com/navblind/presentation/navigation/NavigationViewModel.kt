package com.navblind.presentation.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navblind.domain.model.*
import com.navblind.domain.usecase.RerouteUseCase
import com.navblind.domain.usecase.SearchDestinationUseCase
import com.navblind.domain.usecase.StartNavigationUseCase
import com.navblind.service.location.LocationFusionService
import com.navblind.service.location.RouteDeviationDetector
import com.navblind.service.voice.SpeechRecognitionService
import com.navblind.service.voice.TextToSpeechService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
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
    private val ttsService: TextToSpeechService,
    private val speechRecognitionService: SpeechRecognitionService
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationUiState())
    val uiState: StateFlow<NavigationUiState> = _uiState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    init {
        ttsService.initialize()
        speechRecognitionService.initialize()
        observeLocation()
        observeDeviation()
        fetchInitialLocation()
    }

    private fun fetchInitialLocation() {
        viewModelScope.launch {
            // 위치 추적 시작
            locationFusionService.startTracking()

            // 초기 위치 가져오기 시도
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
        viewModelScope.launch {
            locationFusionService.fusedPosition
                .filterNotNull()
                .collect { position ->
                    _uiState.update { it.copy(currentPosition = position) }

                    // 네비게이션 중이면 이탈 검사
                    if (_uiState.value.isNavigating && _uiState.value.route != null) {
                        routeDeviationDetector.checkDeviation(position)

                        // 도착 확인
                        if (routeDeviationDetector.checkArrival(position)) {
                            handleArrival()
                        }
                    }
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

    private fun observeDeviation() {
        viewModelScope.launch {
            routeDeviationDetector.deviationState.collect { state ->
                when (state) {
                    is RouteDeviationDetector.DeviationState.Deviated -> {
                        handleDeviation()
                    }
                    is RouteDeviationDetector.DeviationState.Warning -> {
                        Log.d(TAG, "Deviation warning: ${state.distanceMeters}m")
                    }
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
                    speakInstruction(instruction)
                }
            }
        }
    }

    fun searchDestination(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true) }

            val currentLocation = _uiState.value.currentPosition?.coordinate

            searchDestinationUseCase(query, currentLocation)
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
                _uiState.update { it.copy(error = "현재 위치를 알 수 없습니다") }
                ttsService.speak("현재 위치를 알 수 없습니다", TextToSpeechService.Priority.HIGH)
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, destination = destination) }

            startNavigationUseCase(origin, destination.toCoordinate(), destination.name)
                .onSuccess { route ->
                    routeDeviationDetector.setRoute(route)
                    locationFusionService.startTracking()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isNavigating = true,
                            route = route,
                            currentInstructionIndex = 0
                        )
                    }

                    // 첫 안내 음성
                    val firstInstruction = route.instructions.firstOrNull()
                    if (firstInstruction != null) {
                        ttsService.speak(
                            "${destination.name}까지 ${route.distanceFormatted} 경로를 안내합니다. " +
                                    firstInstruction.text,
                            TextToSpeechService.Priority.HIGH
                        )
                    }

                    Log.d(TAG, "Navigation started to ${destination.name}")
                }
                .onFailure { error ->
                    Log.e(TAG, "Failed to start navigation", error)
                    _uiState.update { it.copy(isLoading = false, error = "경로를 찾을 수 없습니다") }
                    ttsService.speak("경로를 찾을 수 없습니다", TextToSpeechService.Priority.HIGH)
                }
        }
    }

    fun stopNavigation() {
        routeDeviationDetector.clearRoute()
        locationFusionService.stopTracking()

        _uiState.update {
            it.copy(
                isNavigating = false,
                route = null,
                destination = null,
                currentInstructionIndex = 0
            )
        }

        ttsService.speak("길안내를 종료합니다", TextToSpeechService.Priority.HIGH)
        Log.d(TAG, "Navigation stopped")
    }

    fun startVoiceInput() {
        viewModelScope.launch {
            ttsService.speak("목적지를 말씀해주세요")

            // TTS가 시작될 때까지 대기
            delay(100)
            ttsService.isSpeaking.first { it }

            // TTS가 완료될 때까지 대기
            ttsService.isSpeaking.first { !it }

            // 약간의 지연 추가 (오디오 시스템 안정화)
            delay(500)

            speechRecognitionService.startListening().collect { result ->
                when (result) {
                    is SpeechRecognitionService.RecognitionResult.Success -> {
                        Log.d(TAG, "Voice input: ${result.text}")
                        _uiState.update { it.copy(searchQuery = result.text) }
                        searchDestination(result.text)
                    }
                    is SpeechRecognitionService.RecognitionResult.Partial -> {
                        _uiState.update { it.copy(searchQuery = result.text) }
                    }
                    is SpeechRecognitionService.RecognitionResult.Error -> {
                        Log.e(TAG, "Voice input error: ${result.message}")
                        ttsService.speak("음성을 인식하지 못했습니다. 다시 말씀해주세요.")
                    }
                }
            }
        }
    }

    fun repeatCurrentInstruction() {
        val route = _uiState.value.route ?: return
        val index = _uiState.value.currentInstructionIndex
        if (index < route.instructions.size) {
            speakInstruction(route.instructions[index])
        }
    }

    private suspend fun handleDeviation() {
        val route = _uiState.value.route ?: return
        val position = _uiState.value.currentPosition ?: return

        Log.d(TAG, "Handling route deviation")

        _uiState.update { it.copy(isRerouting = true) }
        ttsService.speak("경로를 재탐색합니다", TextToSpeechService.Priority.HIGH)

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

                val firstInstruction = newRoute.instructions.firstOrNull()
                if (firstInstruction != null) {
                    ttsService.speak(
                        "새로운 경로입니다. ${firstInstruction.text}",
                        TextToSpeechService.Priority.HIGH
                    )
                }

                Log.d(TAG, "Reroute successful")
            }
            .onFailure { error ->
                Log.e(TAG, "Reroute failed", error)
                _uiState.update { it.copy(isRerouting = false, error = "재탐색에 실패했습니다") }
                ttsService.speak("재탐색에 실패했습니다", TextToSpeechService.Priority.HIGH)
            }
    }

    private fun handleArrival() {
        ttsService.speak("목적지에 도착했습니다", TextToSpeechService.Priority.HIGH)

        _uiState.update {
            it.copy(
                isNavigating = false,
                hasArrived = true
            )
        }

        routeDeviationDetector.clearRoute()
        locationFusionService.stopTracking()

        Log.d(TAG, "Arrived at destination")
    }

    private fun speakInstruction(instruction: Instruction) {
        ttsService.speak(instruction.text)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    override fun onCleared() {
        super.onCleared()
        ttsService.release()
        speechRecognitionService.release()
        locationFusionService.stopTracking()
    }

    companion object {
        private const val TAG = "NavigationViewModel"
    }
}

data class NavigationUiState(
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val isNavigating: Boolean = false,
    val isRerouting: Boolean = false,
    val hasArrived: Boolean = false,
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
