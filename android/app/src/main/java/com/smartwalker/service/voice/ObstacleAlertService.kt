package com.smartwalker.service.voice

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.smartwalker.BuildConfig
import com.smartwalker.domain.model.ObjectDetectionResult
import com.smartwalker.domain.model.RelativeDirection
import com.smartwalker.service.detection.HazardPrioritizer
import com.smartwalker.service.detection.ObjectTracker
import com.smartwalker.service.detection.TrajectoryPredictor
import com.smartwalker.service.detection.YoloObjectDetector
import com.smartwalker.service.location.LocationFusionService
import com.smartwalker.service.location.RoadSnappingService
import com.smartwalker.service.location.VisualOdometryService
import com.smartwalker.service.streaming.CameraFrameSource
import com.smartwalker.service.streaming.MjpegCameraSource
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
    private val tts: TextToSpeechService,
    private val locationFusionService: LocationFusionService,
    private val roadSnappingService: RoadSnappingService
) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var detectionJob: Job? = null
    private var frameMonitorJob: Job? = null
    private var previewFrameJob: Job? = null

    /** 클래스명별 마지막 경보 시각 (쿨다운 추적) */
    private val lastAlertTime = mutableMapOf<String, Long>()

    /** 마지막 진동 시각. 즉각 위협 진동을 TTS 쿨다운과 분리해 추적한다. */
    private var lastVibrationTime = 0L

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

    /** 현재 영상 소스 종류 (휴대폰 카메라 / 스마트글래스). UI 표시에 사용됩니다. */
    val cameraSourceType: com.smartwalker.service.streaming.CameraSourceType = cameraSource.sourceType

    private val _lastFrameTimestamp = MutableStateFlow(0L)

    /**
     * 마지막으로 카메라 프레임을 실제 수신한 시각(epoch ms). 0이면 아직 수신 전.
     * isRunning(파이프라인 시작 여부)과 달리, 프레임이 정말 들어오는지를 나타냅니다.
     * DetectionViewModel이 이 값으로 "수신 중 / 신호 없음"을 판단합니다.
     */
    val lastFrameTimestamp: StateFlow<Long> = _lastFrameTimestamp.asStateFlow()

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)

    /**
     * 안내 화면 영상 미리보기용 프레임. YOLO 추론(500ms 간격)과는 별개로
     * [PREVIEW_INTERVAL_MS] 간격으로 샘플링해 화면 표시 부담을 낮춘다.
     */
    val previewFrame: StateFlow<Bitmap?> = _previewFrame.asStateFlow()

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

        // 실제 프레임 수신 시각 추적 — YOLO 처리 속도와 무관하게 raw 프레임마다 기록.
        // 프레임이 끊기면 이 값이 멈추므로, UI가 "신호 없음"을 판단할 수 있다.
        frameMonitorJob = scope.launch {
            cameraSource.frames.collect {
                _lastFrameTimestamp.value = System.currentTimeMillis()
            }
        }

        // UI 미리보기: YOLO 추론용 500ms 샘플링과 별개로 더 촘촘히 샘플링해 화면에 보여준다.
        previewFrameJob = scope.launch {
            cameraSource.frames
                .sample(PREVIEW_INTERVAL_MS)
                .collect { bitmap ->
                    _previewFrame.value = bitmap
                }
        }

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
        frameMonitorJob?.cancel()
        frameMonitorJob = null
        previewFrameJob?.cancel()
        previewFrameJob = null
        _lastFrameTimestamp.value = 0L
        _previewFrame.value = null
        cameraSource.stop()
        objectTracker.reset()
        lastAlertTime.clear()
        Log.d(TAG, "파이프라인 중지")
    }

    private fun announceTopHazard(prioritized: List<HazardPrioritizer.PrioritizedHazard>) {
        val candidate = prioritized
            .firstOrNull { it.priorityScore >= DANGER_THRESHOLD && it.obj.className in ALERT_WHITELIST }
            ?: run {
                // 진단: 감지는 됐으나 경보 기준 미달인 경우 최상위 후보를 남겨 튜닝 근거를 제공.
                // (임계 미달인지 / 화이트리스트 밖인지 구분)
                prioritized.firstOrNull()?.let {
                    Log.d(TAG, "경보 보류: top=%s score=%.2f (임계 %.2f) whitelisted=%b".format(
                        it.obj.className, it.priorityScore, DANGER_THRESHOLD,
                        it.obj.className in ALERT_WHITELIST))
                }
                return
            }

        val now = System.currentTimeMillis()

        // 즉각 위협(isImmediate)은 같은 클래스 TTS 쿨다운(2~4초)에 막히더라도
        // 진동만은 빠르게 전달해 경고 지연을 줄인다 (자체 짧은 쿨다운 사용).
        var vibrated = false
        if (candidate.isImmediate && now - lastVibrationTime >= VIBRATION_COOLDOWN_MS) {
            lastVibrationTime = now
            vibrateForHazard(candidate)
            vibrated = true
        }

        val cooldown = if (candidate.isDynamic) ALERT_COOLDOWN_DYNAMIC_MS else ALERT_COOLDOWN_MS
        if (now - (lastAlertTime[candidate.obj.className] ?: 0L) < cooldown) return

        lastAlertTime[candidate.obj.className] = now

        val message = converter.convert(candidate.obj, suggestAction(candidate, prioritized))
        val ttsPriority = if (candidate.isImmediate) {
            TextToSpeechService.Priority.HIGH
        } else {
            TextToSpeechService.Priority.NORMAL
        }
        _lastAlertMessage.value = message
        tts.speak(message, ttsPriority)
        if (!vibrated) vibrateForHazard(candidate)  // 위에서 즉각 진동을 안 했을 때만
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

    /**
     * 권장 회피 행동을 산출한다 (spec.md: 장애물 + 권장 행동 안내).
     *
     * 시각장애인 안전 원칙상 **틀린 방향 지시는 위험**하므로 보수적으로 처리한다:
     *  - 임박 위협(코앞/정면) → 방향 회피보다 **정지**가 안전.
     *  - 장애물이 한쪽이면 반대쪽으로 안내하되, 그 반대쪽에도 장애물이 있으면 **정지**.
     *  - 정면이면 비어 있는 쪽으로, 양쪽 다 막혀 있으면 **정지**.
     */
    private fun suggestAction(
        candidate: HazardPrioritizer.PrioritizedHazard,
        prioritized: List<HazardPrioritizer.PrioritizedHazard>
    ): String {
        if (candidate.isImmediate) return ACTION_STOP

        // 후보 외의 다른 경보급 장애물이 좌/우 어느 쪽에 있는지 — 회피 방향이 비었는지 판단.
        val others = prioritized.filter { it !== candidate && it.priorityScore >= DANGER_THRESHOLD }
        fun blockedByObstacle(left: Boolean): Boolean = others.any {
            val d = it.obj.relativeDirection
            if (left) d == RelativeDirection.LEFT || d == RelativeDirection.SLIGHTLY_LEFT
            else d == RelativeDirection.RIGHT || d == RelativeDirection.SLIGHTLY_RIGHT
        }

        // ── 경로(보도) 방향 결합 ──
        // 빈 쪽이라도 경로에서 크게 벗어나는(=차도 쪽) 방향으로는 밀지 않는다.
        // 카메라가 못 잡는 차도/연석 위험을 회피 안내가 유발하지 않도록 하는 안전장치.
        val guidance = locationFusionService.fusedPosition.value?.let {
            roadSnappingService.routeGuidance(it.coordinate, it.bestHeading)
        }
        val farOffRoute = (guidance?.offsetMeters ?: 0.0) >= OFF_ROUTE_M
        fun awayFromRoute(moveRight: Boolean): Boolean {
            if (!farOffRoute) return false
            val onRight = guidance?.routeOnRight ?: return false  // 경로 방향 모르면 제약 없음
            return moveRight != onRight                            // 경로 반대쪽으로의 이동 = 멀어짐
        }

        // 해당 방향이 (장애물로) 막혔거나 (경로에서) 멀어지면 그쪽으론 안내하지 않는다.
        fun rightOk() = !blockedByObstacle(left = false) && !awayFromRoute(moveRight = true)
        fun leftOk() = !blockedByObstacle(left = true) && !awayFromRoute(moveRight = false)

        return when (candidate.obj.relativeDirection) {
            RelativeDirection.LEFT, RelativeDirection.SLIGHTLY_LEFT ->
                if (rightOk()) ACTION_MOVE_RIGHT else ACTION_STOP
            RelativeDirection.RIGHT, RelativeDirection.SLIGHTLY_RIGHT ->
                if (leftOk()) ACTION_MOVE_LEFT else ACTION_STOP
            RelativeDirection.CENTER -> when {
                // 둘 다 가능하면 경로(보도) 쪽으로 복귀하는 방향 우선
                rightOk() && leftOk() ->
                    if (guidance?.routeOnRight == false) ACTION_MOVE_LEFT else ACTION_MOVE_RIGHT
                rightOk() -> ACTION_MOVE_RIGHT
                leftOk() -> ACTION_MOVE_LEFT
                else -> ACTION_STOP
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
        private const val PREVIEW_INTERVAL_MS = 150L  // 안내 화면 영상 미리보기 샘플링 간격
        // HazardPrioritizer 점수 임계값. 점수 = dangerLevel×0.6 + collisionRisk×0.4 이고
        // dangerLevel 상한이 1.0이라 danger만으로는 최대 0.6. 0.4는 "코앞+정중앙"만 통과해
        // 전방 5m 보행자도 컷됐다. 0.28로 낮춰 전방 중앙 ~5m 장애물도 경보하도록 한다.
        private const val DANGER_THRESHOLD = 0.28f
        private const val ALERT_COOLDOWN_MS = 4_000L       // 정적 장애물 쿨다운
        private const val ALERT_COOLDOWN_DYNAMIC_MS = 2_000L  // 동적 장애물 쿨다운 (더 짧게)
        private const val VIBRATION_COOLDOWN_MS = 800L     // 즉각 위협 진동 전용 쿨다운 (TTS와 독립)

        // 권장 회피 행동 문구 (spec.md)
        private const val ACTION_STOP = "멈추세요"
        private const val ACTION_MOVE_LEFT = "왼쪽으로 이동하세요"
        private const val ACTION_MOVE_RIGHT = "오른쪽으로 이동하세요"

        // 경로 중심선에서 이만큼 이상 벗어나 있으면, 경로 반대쪽(차도 쪽) 회피를 막는다(m).
        private const val OFF_ROUTE_M = 5.0

        /**
         * 경보 대상 클래스 (한국어). 현재 배포 모델(COCO yolov8n)의 보행 안전 관련 클래스와
         * 일치해야 한다. (YoloObjectDetector.KOREAN_LABELS 참조)
         * 이 집합과 모델 출력 클래스명이 어긋나면 announceTopHazard가 전부 걸러내
         * 음성·진동 경보가 한 번도 울리지 않는다.
         */
        private val ALERT_WHITELIST = setOf(
            "사람", "자전거", "자동차", "오토바이", "버스", "트럭",
            "신호등", "정지 표지판", "소화전", "주차 미터기", "벤치"
        )
    }
}
