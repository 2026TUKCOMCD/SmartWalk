package com.navblind.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navblind.domain.model.DetectedObject
import com.navblind.service.voice.ObstacleAlertService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 실시간 장애물 감지 상태를 관리하는 ViewModel (T072)
 *
 * ObstacleAlertService.detections를 구독하여 UI 표시용 상태를 유지합니다.
 * 음성 경보 자체는 ObstacleAlertService가 직접 처리하므로 여기서는 하지 않습니다.
 *
 * NavigationScreen에서 NavigationViewModel과 함께 사용됩니다.
 * ObstacleAlertService의 start/stop은 NavigationViewModel이 담당합니다.
 */
@HiltViewModel
class DetectionViewModel @Inject constructor(
    private val obstacleAlertService: ObstacleAlertService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetectionUiState())
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    private val _alertMessage = MutableStateFlow("")

    /** 화면에 표시할 마지막 경고 메시지. 3초 후 자동으로 비워집니다. */
    val alertMessage: StateFlow<String> = _alertMessage.asStateFlow()

    init {
        observeDetections()
        observeStreamState()
        observeAlertMessages()
    }

    private fun observeDetections() {
        viewModelScope.launch {
            obstacleAlertService.detections.collect { result ->
                val newEntries = result.objects.map { obj ->
                    DetectionLogEntry(
                        time = TIME_FORMAT.format(Date(result.frameTimestamp)),
                        className = obj.className,
                        distance = obj.estimatedDistance?.let { "%.1fm".format(it) } ?: "?m",
                        direction = when (obj.relativeDirection) {
                            com.navblind.domain.model.RelativeDirection.LEFT -> "좌"
                            com.navblind.domain.model.RelativeDirection.SLIGHTLY_LEFT -> "좌전"
                            com.navblind.domain.model.RelativeDirection.CENTER -> "중앙"
                            com.navblind.domain.model.RelativeDirection.SLIGHTLY_RIGHT -> "우전"
                            com.navblind.domain.model.RelativeDirection.RIGHT -> "우"
                        },
                        dangerLevel = obj.dangerLevel,
                        confidence = obj.confidence
                    )
                }

                _uiState.update { state ->
                    val merged = (newEntries + state.detectionLog).take(LOG_MAX_ENTRIES)
                    state.copy(
                        detectedObjects = result.objects,
                        mostDangerous = result.getMostDangerousObject(),
                        detectionLog = merged
                    )
                }
            }
        }
    }

    private fun observeStreamState() {
        viewModelScope.launch {
            obstacleAlertService.isRunning.collect { running ->
                _uiState.update { it.copy(isStreaming = running) }
            }
        }
    }

    private fun observeAlertMessages() {
        viewModelScope.launch {
            obstacleAlertService.lastAlertMessage.collect { message ->
                if (message.isNotEmpty()) {
                    _alertMessage.value = message
                    delay(3_000L)
                    // 3초 후 현재 메시지가 아직 같은 경우에만 지운다
                    if (_alertMessage.value == message) {
                        _alertMessage.value = ""
                    }
                }
            }
        }
    }

    companion object {
        private const val LOG_MAX_ENTRIES = 30
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    }
}

data class DetectionUiState(
    /** 현재 프레임에서 감지된 모든 객체 */
    val detectedObjects: List<DetectedObject> = emptyList(),
    /** 가장 위험한 객체 (UI 강조 표시용) */
    val mostDangerous: DetectedObject? = null,
    /** MJPEG 스트림 연결 여부 */
    val isStreaming: Boolean = false,
    /** 최근 감지 로그 (최신 항목이 앞에 위치, 최대 30개) */
    val detectionLog: List<DetectionLogEntry> = emptyList()
)

/** 감지 로그 한 줄 */
data class DetectionLogEntry(
    val time: String,
    val className: String,
    val distance: String,
    val direction: String,
    val dangerLevel: Float,
    val confidence: Float
)
