package com.navblind.service.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * ESP32-CAM MJPEG 스트림을 JPEG 프레임 파일 + 메타데이터 CSV 로 저장한다.
 *
 * 저장 구조:
 *   sessionDir/
 *     frames/
 *       frame_00000.jpg
 *       frame_00001.jpg
 *       ...
 *     frames.csv   <- "frameIndex,timestampMs" 한 줄씩
 *
 * Python mock_stream.py 가 이 구조를 읽어 MJPEG 스트림으로 재생한다.
 *
 * MJPEG 파싱 전략:
 *   ESP32-CAM 은 multipart/x-mixed-replace 스트림을 내보낸다.
 *   각 파트 헤더에 Content-Length 가 있으면 그 바이트 수만큼 읽고,
 *   없으면 JPEG SOI(0xFF 0xD8) ~ EOI(0xFF 0xD9) 바이트 스캔으로 추출한다.
 */
class MjpegRecorder(private val sessionDir: File) {

    private val framesDir = File(sessionDir, "frames").also { it.mkdirs() }
    private var metaCsv: BufferedWriter? = null
    private var frameIndex = 0
    private var isRecording = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 스트림이므로 타임아웃 없음
        .build()

    /**
     * [streamUrl] 에 연결해 프레임을 저장한다. suspend 함수이므로 코루틴에서 호출한다.
     * 호출하는 코루틴이 취소되면 자동으로 루프가 종료된다.
     */
    suspend fun record(streamUrl: String) = withContext(Dispatchers.IO) {
        metaCsv = File(sessionDir, "frames.csv").bufferedWriter().also {
            it.write("frameIndex,timestampMs\n")
        }
        isRecording = true

        val request = Request.Builder().url(streamUrl).build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return@withContext
                val stream = body.byteStream()
                val boundary = parseBoundary(response.header("Content-Type"))

                while (isActive && isRecording) {
                    val jpeg = if (boundary != null) {
                        readFrameWithBoundary(stream, boundary)
                    } else {
                        readFrameByMarker(stream)
                    } ?: break

                    saveFrame(jpeg)
                }
            }
        } catch (_: Exception) {
            // 서비스 중단 시 정상 종료
        } finally {
            close()
        }
    }

    fun stop() {
        isRecording = false
    }

    val recordedFrames: Int get() = frameIndex

    // --- MJPEG 파싱 ---

    /** multipart 헤더에서 boundary 문자열 추출 */
    private fun parseBoundary(contentType: String?): String? {
        if (contentType == null) return null
        return contentType
            .split(";")
            .firstOrNull { it.trim().startsWith("boundary=") }
            ?.substringAfter("boundary=")
            ?.trim()
            ?.trim('"')
    }

    /**
     * Content-Type: multipart/x-mixed-replace; boundary=frame
     * 형식일 때: 헤더의 Content-Length 를 읽어 정확한 바이트 수 추출
     */
    private fun readFrameWithBoundary(stream: InputStream, boundary: String): ByteArray? {
        // "--boundary" 라인까지 스킵
        val boundaryLine = "--$boundary"
        if (!skipUntilLine(stream, boundaryLine)) return null

        // 헤더 파싱 (빈 줄 만날 때까지)
        var contentLength = -1
        while (true) {
            val line = readLine(stream) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }

        return if (contentLength > 0) {
            stream.readNBytes(contentLength)
        } else {
            readFrameByMarker(stream) // Content-Length 없으면 마커 스캔
        }
    }

    /**
     * JPEG SOI(0xFF 0xD8) 에서 EOI(0xFF 0xD9) 까지 바이트 스캔.
     * boundary 없는 단순 스트림에 사용한다.
     */
    private fun readFrameByMarker(stream: InputStream): ByteArray? {
        val buf = mutableListOf<Byte>()
        var prev = -1
        var inFrame = false

        while (true) {
            val b = stream.read()
            if (b == -1) return null

            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    buf.add(0xFF.toByte())
                    buf.add(0xD8.toByte())
                    inFrame = true
                }
            } else {
                buf.add(b.toByte())
                if (prev == 0xFF && b == 0xD9) {
                    return buf.toByteArray()
                }
            }
            prev = b
        }
    }

    // --- 유틸 ---

    private fun skipUntilLine(stream: InputStream, target: String): Boolean {
        while (true) {
            val line = readLine(stream) ?: return false
            if (line.startsWith(target)) return true
        }
    }

    private fun readLine(stream: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b == -1) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun saveFrame(jpeg: ByteArray) {
        val name = "frame_%05d.jpg".format(frameIndex)
        File(framesDir, name).writeBytes(jpeg)
        metaCsv?.run {
            write("$frameIndex,${System.currentTimeMillis()}\n")
            flush()
        }
        frameIndex++
    }

    private fun close() {
        metaCsv?.close()
        metaCsv = null
    }
}
