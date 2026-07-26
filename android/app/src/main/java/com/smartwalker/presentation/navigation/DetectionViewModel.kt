package com.smartwalker.presentation.navigation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartwalker.domain.model.DetectedObject
import com.smartwalker.domain.model.RelativeDirection
import com.smartwalker.service.streaming.CameraSourceType
import com.smartwalker.service.voice.ObstacleAlertService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    private val _uiState = MutableStateFlow(
        DetectionUiState(cameraSource = obstacleAlertService.cameraSourceType)
    )
    val uiState: StateFlow<DetectionUiState> = _uiState.asStateFlow()

    /** 화면에 표시할 마지막 경고 메시지. 3초 후 자동으로 비워집니다. */
    val alertMessage: StateFlow<String> = obstacleAlertService.lastAlertMessage
        .transformLatest { msg ->
            emit(msg)
            if (msg.isNotEmpty()) {
                delay(3_000L)
                emit("")
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    init {
        observeDetections()
        observeStreamState()
        observeFrameReception()
        observeLogExpiry()
        observePreviewFrame()
    }

    private fun observeDetections() {
        viewModelScope.launch {
            obstacleAlertService.detections.collect { result ->
                val now = System.currentTimeMillis()
                val newEntries = result.objects.map { obj ->
                    DetectionLogEntry(
                        time = LocalTime.ofInstant(
                            Instant.ofEpochMilli(result.frameTimestamp),
                            ZoneId.systemDefault()
                        ).format(TIME_FORMAT),
                        className = obj.className,
                        distance = obj.estimatedDistance?.let { "%.1fm".format(it) } ?: "?m",
                        direction = obj.relativeDirection.toUiLabel(),
                        dangerLevel = obj.dangerLevel,
                        confidence = obj.confidence,
                        timestampMs = now
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

    /** 안내 화면 영상 미리보기용 프레임 반영 (감지 박스 오버레이와 함께 표시) */
    private fun observePreviewFrame() {
        viewModelScope.launch {
            obstacleAlertService.previewFrame.collect { frame ->
                _uiState.update { it.copy(previewFrame = frame) }
            }
        }
    }

    /**
     * 마지막 프레임 수신 시각을 "수신 중 / 신호 없음"으로 변환합니다.
     *
     * 새 프레임이 올 때마다 타임스탬프가 바뀌고, flatMapLatest가 이전 타이머를 취소해
     * 계속 true를 유지합니다. 프레임이 끊겨 [FRAME_STALE_TIMEOUT_MS] 동안 갱신이 없으면
     * 타이머가 완료되며 false(신호 없음)로 바뀝니다.
     */
    private fun observeFrameReception() {
        viewModelScope.launch {
            obstacleAlertService.lastFrameTimestamp
                .flatMapLatest { ts ->
                    flow {
                        if (ts == 0L) {
                            emit(false)
                        } else {
                            emit(true)
                            delay(FRAME_STALE_TIMEOUT_MS)
                            emit(false)
                        }
                    }
                }
                .collect { receiving ->
                    _uiState.update { it.copy(isReceivingFrames = receiving) }
                }
        }
    }

    /**
     * 감지 로그(데모용 디버그 패널)를 시간이 지나면 사라지게 합니다.
     * 주기적으로 [LOG_ENTRY_TTL_MS]보다 오래된 항목을 제거하고, 모두 사라지면
     * 패널이 자동으로 숨겨집니다(NavigationScreen이 비어 있으면 표시하지 않음).
     */
    private fun observeLogExpiry() {
        viewModelScope.launch {
            while (true) {
                delay(LOG_PRUNE_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - LOG_ENTRY_TTL_MS
                _uiState.update { state ->
                    val pruned = state.detectionLog.filter { it.timestampMs >= cutoff }
                    if (pruned.size == state.detectionLog.size) state
                    else state.copy(detectionLog = pruned)
                }
            }
        }
    }

    companion object {
        private const val LOG_MAX_ENTRIES = 30
        /** 감지 로그 항목 유지 시간 — 이보다 오래되면 사라짐 */
        private const val LOG_ENTRY_TTL_MS = 5_000L
        /** 만료 점검 주기 */
        private const val LOG_PRUNE_INTERVAL_MS = 1_000L
        /** 이 시간 동안 새 프레임이 없으면 "신호 없음"으로 간주 */
        private const val FRAME_STALE_TIMEOUT_MS = 2_000L
        // DateTimeFormatter is immutable — safe for shared use across coroutines
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}

private fun RelativeDirection.toUiLabel() = when (this) {
    RelativeDirection.LEFT -> "좌"
    RelativeDirection.SLIGHTLY_LEFT -> "좌전"
    RelativeDirection.CENTER -> "중앙"
    RelativeDirection.SLIGHTLY_RIGHT -> "우전"
    RelativeDirection.RIGHT -> "우"
}

data class DetectionUiState(
    /** 현재 프레임에서 감지된 모든 객체 */
    val detectedObjects: List<DetectedObject> = emptyList(),
    /** 가장 위험한 객체 (UI 강조 표시용) */
    val mostDangerous: DetectedObject? = null,
    /** MJPEG 스트림 연결 여부 */
    val isStreaming: Boolean = false,
    /** 카메라 프레임이 실제로 들어오고 있는지 (최근 수신 여부) */
    val isReceivingFrames: Boolean = false,
    /** 영상 소스 종류 (휴대폰 카메라 / 스마트글래스) */
    val cameraSource: CameraSourceType = CameraSourceType.ESP32_GLASS,
    /** 최근 감지 로그 (최신 항목이 앞에 위치, 최대 30개) */
    val detectionLog: List<DetectionLogEntry> = emptyList(),
    /** 안내 화면 영상 미리보기용 최신 프레임 (감지 박스 오버레이와 함께 표시) */
    val previewFrame: Bitmap? = null
)

/** 감지 로그 한 줄 */
data class DetectionLogEntry(
    val time: String,
    val className: String,
    val distance: String,
    val direction: String,
    val dangerLevel: Float,
    val confidence: Float,
    /** 항목 생성 시각(epoch ms). TTL 만료로 자동 제거하는 데 사용 */
    val timestampMs: Long
)
