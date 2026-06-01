package com.smartwalker.service.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ESP32-CAM HTTP MJPEG 스트림에서 프레임을 수집합니다.
 *
 * [setUrl]로 힌트 URL을 설정한 뒤 [start]를 호출합니다.
 * JPEG SOI(FF D8) / EOI(FF D9) 마커를 감지하여 각 프레임을 추출합니다.
 * Content-Length 헤더 파싱이 필요 없어 ESP32 설정에 관계없이 동작합니다.
 *
 * 주소 자동 탐색: ESP32가 핫스팟 DHCP로 IP를 받아 주소를 미리 알 수 없으므로,
 * [start] 시 설정된 URL을 먼저 시도하고 실패하면 [Esp32Discovery]가 로컬 서브넷을
 * 스캔해 동작하는 스트림 URL을 찾습니다. 연결이 끊기면 재탐색 후 재연결합니다.
 */
@Singleton
class MjpegCameraSource @Inject constructor(
    private val discovery: Esp32Discovery
) : CameraFrameSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 스트리밍: read timeout 없음
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null
    private var streamUrl: String = ""

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override val sourceType: CameraSourceType = CameraSourceType.ESP32_GLASS

    // 배터리/신호 기반 품질 적응 (T098): ObstacleAlertService의 sample() 간격을 외부에서 제어
    @Volatile private var sampleIntervalMs: Long = 500L
    fun setSampleInterval(ms: Long) { sampleIntervalMs = ms }

    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: Flow<Bitmap> = _frames.asSharedFlow()

    /**
     * 힌트가 될 MJPEG 스트림 URL을 설정합니다. [start] 전에 호출하면 이 URL을
     * 먼저 시도하고, 응답이 없으면 자동 탐색으로 넘어갑니다. 비워 둬도 됩니다.
     */
    fun setUrl(url: String) {
        streamUrl = url
    }

    override fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        streamJob = scope.launch {
            // 마지막으로 정상 연결된 URL. 일시적 끊김(ESP32 브라운아웃·WiFi 흔들림) 시
            // 풀 서브넷 재스캔 대신 이 URL로 바로 재연결한다 — 약한 ESP32-CAM을 재스캔
            // 부하로 더 wedge시키지 않고 빠르게 복구하기 위함.
            var resolvedUrl: String? = null
            var backoffMs = RECONNECT_DELAY_MS
            while (isActive) {
                // 알려진 URL이 있으면 재탐색 없이 그대로, 없으면(최초/IP 변경) 자동 탐색.
                val url = resolvedUrl ?: discovery.resolve(streamUrl.ifEmpty { null })
                if (url == null) {
                    Log.w(TAG, "ESP32 스트림 탐색 실패 — ${backoffMs}ms 후 재시도")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                val connected = connectAndStream(url)  // 스트림이 끊길 때까지 블록

                if (connected) {
                    // 응답을 받은 URL = 유효. 기억해 두고 백오프 리셋(끊겨도 같은 IP로 즉시 복구).
                    resolvedUrl = url
                    backoffMs = RECONNECT_DELAY_MS
                } else {
                    // 연결 자체 실패(연결 거부/타임아웃) = URL 무효. 폰 교체·ESP32 IP 변경 등이면
                    // 다음 루프에서 재탐색하도록 캐시를 비우고 백오프를 키운다.
                    resolvedUrl = null
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }

                if (isActive) {
                    Log.d(TAG, "스트림 종료 — ${backoffMs}ms 후 재연결")
                    delay(backoffMs)
                }
            }
        }
    }

    override fun stop() {
        streamJob?.cancel()
        _isRunning.value = false
        Log.d(TAG, "MJPEG 스트림 중지")
    }

    /**
     * 스트림에 연결해 프레임을 [_frames]로 방출한다. 스트림이 끊길 때까지 블록한다.
     *
     * @return true = 응답을 받아 연결에 성공했음(이후 중간에 끊겨도 일시적 장애로 간주).
     *   false = 연결 자체 실패(연결 거부·타임아웃·비정상 응답) → 호출부가 URL을 무효로 보고 재탐색.
     */
    private suspend fun connectAndStream(url: String): Boolean {
        Log.d(TAG, "MJPEG 스트림 연결: $url")
        val request = Request.Builder().url(url).build()
        var connected = false
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "스트림 연결 실패: ${response.code}")
                    return false
                }
                val inputStream = response.body?.byteStream() ?: run {
                    Log.e(TAG, "응답 본문이 없습니다")
                    return false
                }
                connected = true  // 응답 본문 확보 = URL 유효. 이후 끊김은 일시적 장애.

                val readBuffer = ByteArray(READ_BUFFER_SIZE)
                val frameBuffer = ByteArrayOutputStream(FRAME_BUFFER_CAPACITY)
                var prevByte: Byte = 0
                var inFrame = false

                while (currentCoroutineContext().isActive) {
                    val n = inputStream.read(readBuffer)
                    if (n == -1) break

                    for (i in 0 until n) {
                        val b = readBuffer[i]

                        if (!inFrame) {
                            // JPEG SOI 마커 (FF D8) 감지 → 프레임 캡처 시작
                            if (prevByte == 0xFF.toByte() && b == 0xD8.toByte()) {
                                frameBuffer.reset()
                                frameBuffer.write(0xFF)
                                frameBuffer.write(0xD8)
                                inFrame = true
                            }
                        } else {
                            frameBuffer.write(b.toInt() and 0xFF)
                            // JPEG EOI 마커 (FF D9) 감지 → 프레임 완성
                            if (prevByte == 0xFF.toByte() && b == 0xD9.toByte()) {
                                val data = frameBuffer.toByteArray()
                                BitmapFactory.decodeByteArray(data, 0, data.size)?.let {
                                    _frames.tryEmit(it)
                                }
                                frameBuffer.reset()
                                inFrame = false
                            }
                        }
                        prevByte = b
                    }
                }
            }
        } catch (e: Exception) {
            // 연결 끊김/타임아웃 등 → 상위 루프에서 재탐색·재연결 처리.
            // connected 값으로 "연결 후 끊김(URL 유효)"과 "연결 실패(URL 무효)"를 구분한다.
            if (currentCoroutineContext().isActive) Log.w(TAG, "MJPEG 스트림 오류 (재연결 예정)", e)
        }
        // isRunning 은 start()/stop() 가 관리하므로 여기서 끄지 않는다 (재연결 루프 유지)
        return connected
    }

    companion object {
        private const val TAG = "MjpegCameraSource"
        private const val READ_BUFFER_SIZE = 4096
        private const val FRAME_BUFFER_CAPACITY = 65536
        private const val RECONNECT_DELAY_MS = 1_000L // 재연결/재탐색 기본 간격(백오프 시작값)
        private const val MAX_BACKOFF_MS = 15_000L    // 지수 백오프 상한 — 리부팅 중인 ESP32에 복구 여유
    }
}
