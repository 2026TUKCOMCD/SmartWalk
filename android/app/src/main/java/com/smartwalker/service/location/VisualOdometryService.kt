package com.smartwalker.service.location

import android.util.Log
import com.smartwalker.BuildConfig
import com.smartwalker.domain.model.DetectedObject
import com.smartwalker.domain.model.ObjectCategory
import com.smartwalker.service.detection.ObjectTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 비주얼 오도메트리 (Visual Odometry) 서비스.
 *
 * YOLO 검출 결과에서 정적 랜드마크·고정 장애물을 앵커로 선택하고,
 * ObjectTracker의 바운딩박스 이력을 이용해 프레임 간 카메라(사용자) 이동량을 추정한다.
 *
 * 추정 원리:
 *   - 횡방향 변위 (lateralMeters):
 *       정적 앵커의 수평 픽셀 이동 중앙값을 핀홀 모델로 실제 거리로 변환.
 *       dx_m = -(median_dx_px × depth_m) / focal_px
 *       (앵커가 오른쪽으로 이동 → 카메라가 왼쪽으로 이동 → 사용자가 왼쪽으로 이동)
 *
 *   - 전방 변위 (forwardMeters):
 *       앵커 바운딩박스의 크기 변화율(스케일 팩터)로 추정.
 *       스케일이 커지면(객체가 화면에서 커지면) → 사용자가 앞으로 이동.
 *       forward_m ≈ depth × (1 − 1/sqrt(scale_ratio))
 *       신뢰도는 GPS/PDR보다 낮으므로 KF 가중치를 낮게 설정.
 *
 * 통합:
 *   - ObstacleAlertService.processFrame() 에서 YOLO 추론 후 호출
 *   - LocationFusionService가 displacement Flow를 구독하여 PositionKalmanFilter 보정에 사용
 */
@Singleton
class VisualOdometryService @Inject constructor(
    private val objectTracker: ObjectTracker
) {
    // 카메라 초점 거리 (DistanceEstimator와 동일한 값)
    private val focalLengthPx = if (BuildConfig.USE_LOCAL_CAMERA) 1200f else 800f

    private var frameWidth  = 640
    private var frameHeight = 480

    private val _displacement = MutableStateFlow<VODisplacement?>(null)
    val displacement: StateFlow<VODisplacement?> = _displacement.asStateFlow()

    fun setFrameSize(width: Int, height: Int) {
        frameWidth  = width
        frameHeight = height
    }

    /**
     * 새 프레임의 검출 결과를 받아 카메라 변위를 추정한다.
     * ObstacleAlertService에서 objectTracker.update() 직후 호출한다.
     */
    fun processFrame(detections: List<DetectedObject>) {
        val anchors = selectAnchors(detections)
        if (anchors.size < MIN_ANCHORS) return

        val (lateralM, lateralConf) = estimateLateral(anchors)
        val (forwardM, forwardConf)  = estimateForward(anchors)

        val confidence = lateralConf * 0.7f + forwardConf * 0.3f

        _displacement.value = VODisplacement(
            lateralMeters    = lateralM,
            forwardMeters    = forwardM,
            confidence       = confidence,
            anchorCount      = anchors.size
        )

        Log.v(TAG, "VO: anchors=${anchors.size} " +
                "lateral=%.3fm(%.2f) forward=%.3fm(%.2f)".format(
                    lateralM, lateralConf, forwardM, forwardConf))
    }

    // ── 앵커 선택 ─────────────────────────────────────────────────────────────

    /**
     * 정적이고 안정적으로 추적된 객체를 앵커로 선택한다.
     *
     * 선택 기준:
     *  - 카테고리: LANDMARK, STATIC_OBSTACLE, TRAFFIC_SIGNAL (정적 객체)
     *  - 추적 나이 ≥ MIN_TRACK_AGE 프레임 (추적 안정성 확보)
     *  - 속도 크기 < MAX_ANCHOR_VELOCITY px/frame (이동 중인 객체 제외)
     */
    private fun selectAnchors(detections: List<DetectedObject>): List<DetectedObject> =
        detections.filter { obj ->
            val isStaticClass = obj.category == ObjectCategory.LANDMARK
                    || obj.category == ObjectCategory.STATIC_OBSTACLE
                    || obj.category == ObjectCategory.TRAFFIC_SIGNAL
            if (!isStaticClass) return@filter false

            val history = objectTracker.getHistory(obj.id)
            if (history.size < MIN_TRACK_AGE) return@filter false

            val vel = objectTracker.getVelocity(obj.id)
            sqrt(vel.x * vel.x + vel.y * vel.y) < MAX_ANCHOR_VELOCITY
        }

    // ── 횡방향 변위 추정 (수평 흐름) ─────────────────────────────────────────

    private fun estimateLateral(anchors: List<DetectedObject>): Pair<Float, Float> {
        val flows = anchors.mapNotNull { obj ->
            val history = objectTracker.getHistory(obj.id)
            if (history.size < 2) return@mapNotNull null
            val prev = history[history.size - 2]
            val curr = history[history.size - 1]
            curr.centerX() - prev.centerX()   // dx in pixels
        }
        if (flows.isEmpty()) return 0f to 0f

        val medianDx = median(flows)

        // 픽셀 → 미터: dx_m = -(median_dx_px × depth) / focal
        val avgDepth = anchors.mapNotNull { it.estimatedDistance }
            .average().let { if (it.isFinite()) it.toFloat() else DEFAULT_DEPTH_M }
        val lateralM = -(medianDx * avgDepth) / focalLengthPx

        // 신뢰도: 앵커 수 × 흐름 일관성(MAD)
        val mad = madFloat(flows, medianDx)
        val countScore  = (anchors.size.toFloat() / IDEAL_ANCHOR_COUNT).coerceIn(0f, 1f)
        val cohesion    = (1f - (mad / MAX_FLOW_MAD_PX).coerceIn(0f, 1f))
        val confidence  = (countScore * 0.4f + cohesion * 0.6f)

        return lateralM to confidence
    }

    // ── 전방 변위 추정 (스케일 변화) ─────────────────────────────────────────

    /**
     * 바운딩박스 면적 변화율(스케일 팩터 k)로 전방 이동을 추정한다.
     *
     * 핀홀 모델에서 면적 ∝ 1/d² 이므로:
     *   k = area_{t} / area_{t-1}  (현재/이전)
     *   k > 1 → 더 가까워짐 → 전진
     *   d_{t} = depth, d_{t-1} = depth / sqrt(k)
     *   forward_m = depth × (1 − 1/sqrt(k))
     */
    private fun estimateForward(anchors: List<DetectedObject>): Pair<Float, Float> {
        data class ScaleEntry(val scale: Float, val depth: Float)

        val entries = anchors.mapNotNull { obj ->
            val history = objectTracker.getHistory(obj.id)
            if (history.size < 2) return@mapNotNull null
            val prev = history[history.size - 2]
            val curr = history[history.size - 1]

            val prevArea = prev.width() * prev.height()
            val currArea = curr.width() * curr.height()
            if (prevArea < MIN_BOX_AREA_PX2 || currArea < MIN_BOX_AREA_PX2) return@mapNotNull null

            val scale = currArea / prevArea
            val depth = obj.estimatedDistance ?: DEFAULT_DEPTH_M
            ScaleEntry(scale, depth)
        }
        if (entries.isEmpty()) return 0f to 0f

        val medianScale = median(entries.map { it.scale })
        val avgDepth    = entries.map { it.depth }.average().toFloat()

        // 스케일이 1에 가까우면 이동 없음
        if (abs(medianScale - 1f) < SCALE_CHANGE_DEAD_ZONE) return 0f to 0f

        val sqrtScale  = sqrt(medianScale)
        val forwardM   = avgDepth * (1f - 1f / sqrtScale.coerceAtLeast(0.01f))

        // 스케일 추정은 노이즈에 민감 → 신뢰도 낮게
        val countScore = (entries.size.toFloat() / IDEAL_ANCHOR_COUNT).coerceIn(0f, 0.7f)
        val confidence = countScore * 0.5f   // 최대 0.35 (PDR보다 낮음)

        return forwardM to confidence
    }

    // ── 수학 유틸 ─────────────────────────────────────────────────────────────

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid    = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f
        else sorted[mid]
    }

    /** Median Absolute Deviation — 이상값에 강건한 분산 척도 */
    private fun madFloat(values: List<Float>, med: Float): Float =
        median(values.map { abs(it - med) })

    companion object {
        private const val TAG = "VisualOdometry"

        private const val MIN_ANCHORS            = 2     // 최소 앵커 수
        private const val IDEAL_ANCHOR_COUNT     = 5     // 이 이상이면 count 신뢰도 MAX
        private const val MIN_TRACK_AGE          = 3     // 최소 추적 프레임 수
        private const val MAX_ANCHOR_VELOCITY    = 3f    // px/frame, 정적 판별 임계값
        private const val MAX_FLOW_MAD_PX        = 5f    // 흐름 일관성 임계 (px)
        private const val DEFAULT_DEPTH_M        = 5f    // 깊이 추정 불가 시 기본값 (m)
        private const val MIN_BOX_AREA_PX2       = 100f  // 스케일 추정에 쓸 최소 박스 면적
        private const val SCALE_CHANGE_DEAD_ZONE = 0.02f // 이 이하의 스케일 변화는 무시
    }
}

/**
 * 비주얼 오도메트리로 추정된 카메라 변위.
 *
 * @param lateralMeters  좌(-) / 우(+) 이동 (m). 사용자 진행 방향 기준.
 * @param forwardMeters  전진(+) / 후진(-) 이동 (m). 스케일 변화로 추정.
 * @param confidence     0–1, 높을수록 신뢰. KF 가중치에 사용.
 * @param anchorCount    추정에 사용된 정적 앵커 수.
 */
data class VODisplacement(
    val lateralMeters:  Float,
    val forwardMeters:  Float,
    val confidence:     Float,
    val anchorCount:    Int,
    val timestamp:      Long = System.currentTimeMillis()
) {
    /** 총 변위 크기 (m) */
    val magnitudeMeters: Float
        get() = sqrt(lateralMeters * lateralMeters + forwardMeters * forwardMeters)

    /**
     * 변위가 유의미한지 (최소 신뢰도 + 최소 이동량).
     *
     * VO는 정적 앵커 ≥2개와 안정 추적이 필요해 빈 보도에선 거의 발동하지 않고,
     * 전방 추정은 노이즈가 크다. 따라서 측위의 보조(기회적 보너스)로만 쓰도록
     * 신뢰도 게이트를 높게(0.45) 두어, 확실할 때만 KF 예측에 반영한다.
     */
    val isSignificant: Boolean
        get() = confidence >= MIN_SIGNIFICANT_CONFIDENCE && magnitudeMeters > 0.01f

    private companion object {
        const val MIN_SIGNIFICANT_CONFIDENCE = 0.45f
    }
}
