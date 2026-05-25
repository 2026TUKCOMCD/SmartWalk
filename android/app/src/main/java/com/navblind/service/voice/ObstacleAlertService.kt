package com.navblind.service.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.navblind.BuildConfig
import com.navblind.domain.model.ObjectDetectionResult
import com.navblind.service.detection.HazardPrioritizer
import com.navblind.service.detection.ObjectTracker
import com.navblind.service.detection.TrajectoryPredictor
import com.navblind.service.detection.YoloObjectDetector
import com.navblind.service.location.VisualOdometryService
import com.navblind.service.streaming.CameraFrameSource
import com.navblind.service.streaming.MjpegCameraSource
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MJPEG 스트림 → YOLO 추론 → 한국어 음성 경보 파이프라인 (T070)
 *
 * 흐름:
 *   MjpegCameraSource.frames
 *     → sample(500ms)
 *     → YoloObjectDetector.detect()
 *     → 위험도 0.5 이상 객체 중 최대 위험 1개 선택
 *     → 클래스별 쿨다운(4초) 적용
 *     → DetectionToSpeechConverter → TextToSpeechService
 *
 * 사용:
 *   start(BuildConfig.GLASS_STREAM_URL)  // 경로 안내 시작 시
 *   stop()                               // 경로 안내 종료 시
 *
 * detections Flow는 DetectionViewModel이 구독하여 UI 상태로 노출합니다.
 */
@Singleton
class ObstacleAlertService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraSource: CameraFrameSource,
    private val yoloDetector: YoloObjectDetector,
    private val objectTracker: ObjectTracker,
    private val trajectoryPredictor: TrajectoryPredictor,
    private val hazardPrioritizer: HazardPrioritizer,
    private val visualOdometry: VisualOdometryService,
    private val converter: DetectionToSpeechConverter,
    private val tts: TextToSpeechService
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var detectionJob: Job? = null

    /** 클래스명별 마지막 경보 시각 (쿨다운 추적) */
    private val lastAlertTime = mutableMapOf<String, Long>()

    private val _detections = MutableSharedFlow<ObjectDetectionResult>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 추론 결과 스트림. DetectionViewModel이 UI 표시용으로 구독합니다. */
    val detections: Flow<ObjectDetectionResult> = _detections.asSharedFlow()

    private val _lastAlertMessage = MutableStateFlow("")

    /** 마지막으로 TTS로 읽은 경고 메시지. DetectionViewModel이 UI 오버레이로 표시합니다. */
    val lastAlertMessage: StateFlow<String> = _lastAlertMessage.asStateFlow()

    /** 카메라 소스 실행 상태 */
    val isRunning: StateFlow<Boolean> = cameraSource.isRunning

    /**
     * 장애물 감지 파이프라인을 시작합니다.
     *
     * @param streamUrl MJPEG 스트림 URL (ESP32-CAM 사용 시)
     *   - 에뮬레이터: BuildConfig.GLASS_STREAM_URL = http://10.0.2.2:8081/stream
     *   - 실기기:     BuildConfig.GLASS_STREAM_URL = http://192.168.4.1/stream
     *   - USE_LOCAL_CAMERA=true 이면 무시되고 스마트폰 내장 카메라를 사용합니다.
     */
    fun start(streamUrl: String = BuildConfig.GLASS_STREAM_URL) {
        if (detectionJob?.isActive == true) return

        yoloDetector.initialize()
        (cameraSource as? MjpegCameraSource)?.setUrl(streamUrl)
        cameraSource.start()

        detectionJob = scope.launch {
            cameraSource.frames
                .sample(DETECTION_INTERVAL_MS)
                .collect { bitmap ->
                    val raw = yoloDetector.detect(bitmap)

                    // 1. 안정적인 trackId 부여 + 이력 관리
                    val tracked = objectTracker.update(raw.objects)

                    // 2. 비주얼 오도메트리: 정적 앵커의 흐름으로 카메라 변위 추정
                    visualOdometry.setFrameSize(bitmap.width, bitmap.height)
                    visualOdometry.processFrame(tracked)

                    // 3. 궤적 기반 충돌위험도 계산
                    val collisionRisks = trajectoryPredictor.predict(
                        tracked, bitmap.width, bitmap.height
                    )

                    // 4. 최종 우선순위 정렬
                    val prioritized = hazardPrioritizer.prioritize(tracked, collisionRisks)

                    val result = raw.copy(objects = prioritized.map { it.obj })
                    _detections.tryEmit(result)
                    announceTopHazard(prioritized)
                }
        }

        Log.d(TAG, "파이프라인 시작: $streamUrl")
    }

    /** 장애물 감지와 카메라 소스를 중지합니다. */
    fun stop() {
        detectionJob?.cancel()
        detectionJob = null
        cameraSource.stop()
        objectTracker.reset()
        lastAlertTime.clear()
        Log.d(TAG, "파이프라인 중지")
    }

    private fun announceTopHazard(prioritized: List<HazardPrioritizer.PrioritizedHazard>) {
        val candidate = prioritized
            .firstOrNull { it.priorityScore >= DANGER_THRESHOLD && it.obj.className in ALERT_WHITELIST }
            ?: return

        val now = System.currentTimeMillis()
        val cooldown = if (candidate.isDynamic) ALERT_COOLDOWN_DYNAMIC_MS else ALERT_COOLDOWN_MS
        if (now - (lastAlertTime[candidate.obj.className] ?: 0L) < cooldown) return

        lastAlertTime[candidate.obj.className] = now

        val message = converter.convert(candidate.obj)
        val ttsPriority = if (candidate.isImmediate) {
            TextToSpeechService.Priority.HIGH
        } else {
            TextToSpeechService.Priority.NORMAL
        }
        _lastAlertMessage.value = message
        tts.speak(message, ttsPriority)
        vibrateForHazard(candidate)  // T146: 진동 피드백
        Log.d(TAG, "경보: \"$message\" (score=%.2f, dynamic=%b)".format(
            candidate.priorityScore, candidate.isDynamic))

        // T147: 2위 위협도 별도 쿨다운 내라면 추가 큐
        prioritized.drop(1)
            .filter { it.priorityScore >= DANGER_THRESHOLD && it.obj.className in ALERT_WHITELIST }
            .take(1)  // 최대 1개 추가
            .forEach { second ->
                val now2 = System.currentTimeMillis()
                val cooldown2 = if (second.isDynamic) ALERT_COOLDOWN_DYNAMIC_MS else ALERT_COOLDOWN_MS
                if (now2 - (lastAlertTime[second.obj.className] ?: 0L) >= cooldown2) {
                    lastAlertTime[second.obj.className] = now2
                    tts.speak(converter.convert(second.obj), TextToSpeechService.Priority.NORMAL)
                }
            }
    }

    private fun vibrateForHazard(hazard: HazardPrioritizer.PrioritizedHazard) {
        if (!vibrator.hasVibrator()) return
        val pattern = if (hazard.isImmediate) longArrayOf(0, 200, 100, 200) else longArrayOf(0, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    companion object {
        private const val TAG = "ObstacleAlertService"
        private const val DETECTION_INTERVAL_MS = 500L
        private const val DANGER_THRESHOLD = 0.4f          // HazardPrioritizer 점수 임계값
        private const val ALERT_COOLDOWN_MS = 4_000L       // 정적 장애물 쿨다운
        private const val ALERT_COOLDOWN_DYNAMIC_MS = 2_000L  // 동적 장애물 쿨다운 (더 짧게)

        /** 보행 안전과 관련된 경보 대상 클래스 (한국어). EYE-U 9개 + 보행로 특화 4개 */
        private val ALERT_WHITELIST = setOf(
            "사람", "자전거", "자동차", "오토바이", "버스", "트럭",
            "신호등", "정지 표지판", "소화전", "주차 미터기",
            "벤치", "볼라드", "라바콘"
        )
    }
}
