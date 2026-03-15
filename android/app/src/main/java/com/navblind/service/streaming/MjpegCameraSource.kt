package com.navblind.service.streaming

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * [setUrl]로 스트림 URL을 설정한 뒤 [start]를 호출합니다.
 * JPEG SOI(FF D8) / EOI(FF D9) 마커를 감지하여 각 프레임을 추출합니다.
 * Content-Length 헤더 파싱이 필요 없어 ESP32 설정에 관계없이 동작합니다.
 */
@Singleton
class MjpegCameraSource @Inject constructor() : CameraFrameSource {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 스트리밍: read timeout 없음
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job? = null
    private var streamUrl: String = ""

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: Flow<Bitmap> = _frames.asSharedFlow()

    /** 연결할 MJPEG 스트림 URL을 설정합니다. [start] 전에 호출해야 합니다. */
    fun setUrl(url: String) {
        streamUrl = url
    }

    override fun start() {
        if (_isRunning.value) return
        if (streamUrl.isEmpty()) {
            Log.w(TAG, "URL이 설정되지 않았습니다. setUrl() 먼저 호출하세요.")
            return
        }
        _isRunning.value = true
        streamJob = scope.launch { connectAndStream(streamUrl) }
    }

    override fun stop() {
        streamJob?.cancel()
        _isRunning.value = false
        Log.d(TAG, "MJPEG 스트림 중지")
    }

    private suspend fun connectAndStream(url: String) {
        Log.d(TAG, "MJPEG 스트림 연결: $url")
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "스트림 연결 실패: ${response.code}")
                    return
                }
                val inputStream = response.body?.byteStream() ?: run {
                    Log.e(TAG, "응답 본문이 없습니다")
                    return
                }

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
            if (currentCoroutineContext().isActive) Log.e(TAG, "MJPEG 스트림 오류", e)
        } finally {
            _isRunning.value = false
        }
    }

    companion object {
        private const val TAG = "MjpegCameraSource"
        private const val READ_BUFFER_SIZE = 4096
        private const val FRAME_BUFFER_CAPACITY = 65536
    }
}
