package com.smartwalker.service.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smartwalker.service.location.LocationFusionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Inject

/**
 * 테스트 데이터 수집 포그라운드 서비스.
 *
 * 수집 항목:
 *   - GPS 궤적   → route.gpx       (에뮬레이터 GPX 재생용)
 *
 * 참고: YOLO 학습용 영상 프레임 수집(CameraX 내장 카메라 / ESP32 MJPEG)은
 * 모델 학습이 완료되어 중단했습니다. CameraX 발열·배터리 이슈도 함께 회피합니다.
 *
 * 실행:  DataCollectionService.start(context)
 * 종료:  DataCollectionService.stop(context)
 *   -> /sdcard/Android/data/com.smartwalker/files/sessions/<timestamp>/ 에 저장됨
 */
@AndroidEntryPoint
class DataCollectionService : Service() {

    @Inject lateinit var locationFusionService: LocationFusionService

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var gpxRecorder: GpxRecorder? = null
    private var sessionDir: File? = null

    // --- 생명주기 ---

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
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

    private fun startRecording() {
        val dir = createSessionDir().also { sessionDir = it }
        Log.i(TAG, "세션 시작: ${dir.absolutePath}")

        // GPS 궤적 기록
        gpxRecorder = GpxRecorder(File(dir, "route.gpx")).also {
            it.start(locationFusionService.fusedPosition)
        }

        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun stopRecording() {
        gpxRecorder?.stop()

        val dir = sessionDir
        if (dir != null) {
            val gpxPoints = gpxRecorder?.recordedPoints ?: 0
            Log.i(TAG, "세션 완료: GPS=$gpxPoints 포인트 → ${dir.name}")
            writeSessionMeta(dir, gpxPoints)
        }

        gpxRecorder = null
        sessionDir = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- 헬퍼 ---

    private fun createSessionDir(): File {
        val sessionsRoot = File(getExternalFilesDir(null), "sessions")
        val dir = File(sessionsRoot, System.currentTimeMillis().toString())
        dir.mkdirs()
        return dir
    }

    private fun writeSessionMeta(dir: File, gpxPoints: Int) {
        File(dir, "meta.txt").writeText(
            """session_id=${dir.name}
recorded_at=${java.time.Instant.now()}
gps_points=$gpxPoints
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NavBlind 데이터 수집 중")
            .setContentText("GPS 궤적 기록 중...")
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

        const val ACTION_START = "com.smartwalker.recording.START"
        const val ACTION_STOP = "com.smartwalker.recording.STOP"

        fun start(context: Context) {
            val intent = Intent(context, DataCollectionService::class.java).apply {
                action = ACTION_START
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
