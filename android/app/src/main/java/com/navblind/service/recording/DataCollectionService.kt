package com.navblind.service.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.navblind.BuildConfig
import com.navblind.service.location.GeospatialService
import com.navblind.service.location.LocationFusionService
import com.navblind.service.streaming.LocalCameraSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * 테스트 데이터 수집 포그라운드 서비스.
 *
 * 역할:
 *   - GPS 궤적 → route.gpx  (에뮬레이터 GPX 재생용)
 *   - 카메라 프레임 → frames/frame_NNNNN.jpg + frames.csv  (mock_stream.py 재생용)
 *
 * BuildConfig.USE_LOCAL_CAMERA == true (디버그):
 *   CameraX 내장 카메라로 프레임 저장
 * BuildConfig.USE_LOCAL_CAMERA == false (릴리즈):
 *   ESP32-CAM MJPEG 스트림으로 프레임 저장
 *
 * 실행:
 *   DataCollectionService.start(context)
 *
 * 종료:
 *   DataCollectionService.stop(context)
 *   -> /sdcard/Android/data/com.navblind/files/sessions/<timestamp>/ 에 저장됨
 *   -> adb pull 후 collect_session.sh 사용
 */
@AndroidEntryPoint
class DataCollectionService : Service() {

    @Inject lateinit var locationFusionService: LocationFusionService
    @Inject lateinit var localCameraSource: LocalCameraSource
    @Inject lateinit var geospatialService: GeospatialService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var gpxRecorder: GpxRecorder? = null
    private var mjpegRecorder: MjpegRecorder? = null
    private var mjpegJob: Job? = null
    private var frameJob: Job? = null
    private var sessionDir: File? = null
    private var localFrameCount = 0
    /** ARCore 녹화 파일 경로. !USE_LOCAL_CAMERA 시에만 사용. */
    private var arcoreRecordingPath: String? = null

    // --- 생명주기 ---

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: DEFAULT_STREAM_URL
                startRecording(streamUrl)
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    // --- 녹화 제어 ---

    private fun startRecording(streamUrl: String) {
        val dir = createSessionDir().also { sessionDir = it }
        localFrameCount = 0
        Log.i(TAG, "세션 시작: ${dir.absolutePath}")

        // GPS 기록
        gpxRecorder = GpxRecorder(File(dir, "route.gpx")).also {
            it.start(locationFusionService.fusedPosition)
        }

        if (BuildConfig.USE_LOCAL_CAMERA) {
            // 내장 카메라 프레임 저장
            val framesDir = File(dir, "frames").also { it.mkdirs() }
            val csvFile = File(dir, "frames.csv").also {
                it.writeText("frame_index,timestamp_ms,filename\n")
            }

            localCameraSource.start()
            frameJob = scope.launch {
                localCameraSource.frames.collect { bitmap ->
                    saveFrame(bitmap, framesDir, csvFile, localFrameCount)
                    localFrameCount++
                }
            }
            Log.i(TAG, "내장 카메라 프레임 저장 시작")
        } else {
            // ESP32-CAM MJPEG 스트림 기록
            mjpegRecorder = MjpegRecorder(dir)
            mjpegJob = CoroutineScope(Dispatchers.IO).launch {
                mjpegRecorder?.record(streamUrl)
            }
            Log.i(TAG, "MJPEG 스트림 기록 시작: $streamUrl")

            // ARCore 세션 녹화 (.mp4): VPS 에뮬레이터 테스트용 데이터
            // ESP32 모드에서는 폰 카메라를 CameraX가 점유하지 않으므로 ARCore가 사용 가능
            val arcorePath = "${dir.absolutePath}/session.mp4"
            arcoreRecordingPath = arcorePath
            geospatialService.startLive()
            scope.launch {
                // isAvailable == true가 될 때까지 최대 10초 대기 (세션 초기화 시간)
                val ready = withTimeoutOrNull(10_000L) {
                    geospatialService.isAvailable.first { it }
                }
                if (ready == true) {
                    geospatialService.startRecording(arcorePath)
                } else {
                    Log.w(TAG, "ARCore 초기화 시간 초과 (10s), 세션 녹화 없이 계속")
                    arcoreRecordingPath = null
                }
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopRecording() {
        gpxRecorder?.stop()

        if (BuildConfig.USE_LOCAL_CAMERA) {
            localCameraSource.stop()
            frameJob?.cancel()
        } else {
            mjpegRecorder?.stop()
            mjpegJob?.cancel()
            // ARCore 녹화 중지 및 세션 해제
            if (arcoreRecordingPath != null) {
                geospatialService.stopRecording()
            }
            geospatialService.release()
            arcoreRecordingPath = null
        }

        val dir = sessionDir
        if (dir != null) {
            val gpxPoints = gpxRecorder?.recordedPoints ?: 0
            val frames = if (BuildConfig.USE_LOCAL_CAMERA) localFrameCount
                         else mjpegRecorder?.recordedFrames ?: 0
            Log.i(TAG, "세션 완료: GPS=$gpxPoints 포인트, 영상=$frames 프레임 → ${dir.name}")
            writeSessionMeta(dir, gpxPoints, frames)
        }

        gpxRecorder = null
        mjpegRecorder = null
        mjpegJob = null
        frameJob = null
        sessionDir = null
        localFrameCount = 0

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- 헬퍼 ---

    private fun saveFrame(bitmap: Bitmap, framesDir: File, csvFile: File, index: Int) {
        val filename = "frame_%05d.jpg".format(index)
        try {
            FileOutputStream(File(framesDir, filename)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            csvFile.appendText("$index,${System.currentTimeMillis()},$filename\n")
        } catch (e: Exception) {
            Log.w(TAG, "프레임 저장 실패: $filename", e)
        }
    }

    private fun createSessionDir(): File {
        val sessionsRoot = File(getExternalFilesDir(null), "sessions")
        val dir = File(sessionsRoot, System.currentTimeMillis().toString())
        dir.mkdirs()
        return dir
    }

    private fun writeSessionMeta(dir: File, gpxPoints: Int, frames: Int) {
        val arcoreRecording = if (!BuildConfig.USE_LOCAL_CAMERA && File(dir, "session.mp4").exists()) {
            "session.mp4"
        } else {
            "none"
        }
        File(dir, "meta.txt").writeText(
            """session_id=${dir.name}
recorded_at=${java.time.Instant.now()}
camera_source=${if (BuildConfig.USE_LOCAL_CAMERA) "local" else "esp32cam"}
gps_points=$gpxPoints
video_frames=$frames
arcore_recording=$arcoreRecording
""")
    }

    // --- 알림 ---

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        val stopIntent = Intent(this, DataCollectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val cameraLabel = if (BuildConfig.USE_LOCAL_CAMERA) "내장 카메라" else "ESP32-CAM"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NavBlind 데이터 수집 중")
            .setContentText("GPS + $cameraLabel 기록 중...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "중지", stopPi)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "데이터 수집", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // --- 정적 팩토리 ---

    companion object {
        private const val TAG = "DataCollectionService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "data_collection"

        const val ACTION_START = "com.navblind.recording.START"
        const val ACTION_STOP = "com.navblind.recording.STOP"
        const val EXTRA_STREAM_URL = "stream_url"

        private const val DEFAULT_STREAM_URL = "http://192.168.4.1/stream"

        fun start(context: Context, glassStreamUrl: String = DEFAULT_STREAM_URL) {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STREAM_URL, glassStreamUrl)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
