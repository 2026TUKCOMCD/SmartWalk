package com.navblind.service.streaming

import android.util.Log
import com.navblind.domain.model.SmartGlasses
import com.navblind.domain.repository.DeviceRepository
import com.navblind.service.voice.TextToSpeechService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ESP32-CAM 스마트글래스 연결 관리 서비스.
 *
 * 기능:
 * - 기기 등록 및 IP 연결
 * - 자동 재연결 (최대 5회, 지수 백오프)
 * - 배터리 수신 후 음성 경보
 * - MJPEG 품질 적응 (배터리/신호 기반)
 */
@Singleton
class SmartGlassesConnectionService @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val mjpegSource: MjpegCameraSource,
    private val tts: TextToSpeechService
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private var reconnectJob: Job? = null
    private var currentDeviceId: UUID? = null
    private var retryCount = 0

    // ─── 연결 ────────────────────────────────────────────────────────────────

    fun connect(deviceId: UUID, ipAddress: String) {
        currentDeviceId = deviceId
        retryCount = 0
        attemptConnection(deviceId, ipAddress)
    }

    private fun attemptConnection(deviceId: UUID, ipAddress: String) {
        scope.launch {
            _connectionState.value = ConnectionState.Connecting
            deviceRepository.updateConnection(deviceId, ipAddress, null)
                .onSuccess { device ->
                    val streamUrl = device.streamUrl ?: "http://$ipAddress/stream"
                    mjpegSource.setUrl(streamUrl)
                    mjpegSource.start()
                    _connectionState.value = ConnectionState.Connected(device)
                    retryCount = 0
                    tts.speak("스마트글래스가 연결되었습니다", TextToSpeechService.Priority.NORMAL)
                    Log.i(TAG, "Connected to $streamUrl")
                }
                .onFailure { error ->
                    Log.w(TAG, "Connection failed: ${error.message}")
                    scheduleReconnect(deviceId, ipAddress)
                }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        val id = currentDeviceId
        if (id != null) {
            scope.launch {
                deviceRepository.disconnect(id)
            }
        }
        mjpegSource.stop()
        _connectionState.value = ConnectionState.Disconnected
        tts.speak("스마트글래스 연결이 종료되었습니다", TextToSpeechService.Priority.NORMAL)
        Log.i(TAG, "Disconnected")
    }

    // ─── 자동 재연결 (지수 백오프) ───────────────────────────────────────────

    private fun scheduleReconnect(deviceId: UUID, ipAddress: String) {
        if (retryCount >= MAX_RETRIES) {
            _connectionState.value = ConnectionState.Failed("최대 재연결 횟수 초과")
            tts.speak("스마트글래스 연결에 실패했습니다", TextToSpeechService.Priority.HIGH)
            return
        }
        val delayMs = RETRY_BASE_DELAY_MS * (1L shl retryCount)
        retryCount++
        _connectionState.value = ConnectionState.Reconnecting(retryCount)
        Log.i(TAG, "Reconnect attempt $retryCount in ${delayMs}ms")

        reconnectJob = scope.launch {
            delay(delayMs)
            attemptConnection(deviceId, ipAddress)
        }
    }

    // ─── 배터리 수신 (T101-B) ────────────────────────────────────────────────

    fun updateBattery(level: Int) {
        val previous = _batteryLevel.value
        _batteryLevel.value = level

        if (level <= 10 && (previous == null || previous > 10)) {
            tts.speak("스마트글래스 배터리가 매우 부족합니다. ${level}%입니다", TextToSpeechService.Priority.HIGH)
        } else if (level <= 20 && (previous == null || previous > 20)) {
            tts.speak("스마트글래스 배터리가 부족합니다. ${level}%입니다", TextToSpeechService.Priority.NORMAL)
        }

        // 배터리 낮으면 스트림 품질 낮춤 (T098)
        adjustStreamQuality(level)
    }

    private fun adjustStreamQuality(batteryLevel: Int) {
        val interval = when {
            batteryLevel <= 10 -> 2000L  // 2초마다 한 프레임
            batteryLevel <= 20 -> 1000L  // 1초
            else -> 500L                 // 정상
        }
        mjpegSource.setSampleInterval(interval)
    }

    // ─── 연결 상태 타입 ──────────────────────────────────────────────────────

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val device: SmartGlasses) : ConnectionState()
        data class Reconnecting(val attempt: Int) : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    companion object {
        private const val TAG = "GlassesConnectionService"
        private const val MAX_RETRIES = 5
        private const val RETRY_BASE_DELAY_MS = 2_000L
    }
}
