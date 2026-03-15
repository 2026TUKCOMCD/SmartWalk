package com.navblind.service.detection

import android.graphics.RectF
import android.util.Log
import com.navblind.BuildConfig
import com.navblind.domain.model.DetectedObject
import com.navblind.domain.model.ObjectCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.tan

/**
 * 단안 카메라에서 정지 장애물까지의 대략적인 거리를 추정하는 서비스입니다.
 *
 * research.md에 정의된 방법들을 사용합니다:
 * 1. 참조 객체 방법: 알려진 크기의 객체(사람 170cm, 신호등 30cm 등)를 사용
 * 2. 지면 평면 가정: 객체가 지면에 접해있다고 가정하고 카메라 높이/각도로 계산
 *
 * 정확한 거리 측정은 불가능하지만, 안전 경고에 충분한 대략적인 거리를 제공합니다.
 */
@Singleton
class DistanceEstimator @Inject constructor() {

    // 카메라 파라미터: USE_LOCAL_CAMERA 플래그에 따라 분기
    private var cameraFocalLengthPx: Float = if (BuildConfig.USE_LOCAL_CAMERA) {
        FOCAL_LENGTH_PHONE_PX   // 스마트폰 후면 카메라
    } else {
        FOCAL_LENGTH_GLASS_PX   // ESP32-CAM (스마트글래스)
    }
    private var cameraHeightMeters: Float = if (BuildConfig.USE_LOCAL_CAMERA) {
        CAMERA_HEIGHT_PHONE_METERS   // 손에 들고 촬영
    } else {
        CAMERA_HEIGHT_GLASS_METERS   // 안경 착용 높이
    }
    private var cameraPitchDegrees: Float = if (BuildConfig.USE_LOCAL_CAMERA) {
        CAMERA_PITCH_PHONE_DEGREES   // 약간 내려보는 각도
    } else {
        CAMERA_PITCH_GLASS_DEGREES   // 수평에 가까운 각도
    }

    // 프레임 크기
    private var frameWidth: Int = 640
    private var frameHeight: Int = 480

    /**
     * 카메라 파라미터를 설정합니다.
     *
     * @param focalLengthPx 초점 거리 (픽셀 단위)
     * @param heightMeters 카메라 높이 (미터)
     * @param pitchDegrees 카메라 기울기 (도, 아래 방향이 양수)
     */
    fun setCameraParams(
        focalLengthPx: Float = cameraFocalLengthPx,
        heightMeters: Float = cameraHeightMeters,
        pitchDegrees: Float = cameraPitchDegrees
    ) {
        cameraFocalLengthPx = focalLengthPx
        cameraHeightMeters = heightMeters
        cameraPitchDegrees = pitchDegrees
        Log.d(TAG, "Camera params set: focal=$focalLengthPx, height=$heightMeters, pitch=$pitchDegrees")
    }

    /**
     * 프레임 크기를 설정합니다.
     */
    fun setFrameSize(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
    }

    /**
     * 객체까지의 거리를 추정합니다.
     *
     * @param detectedObject 인식된 객체
     * @return 추정 거리 (미터), 추정 불가 시 null
     */
    fun estimateDistance(detectedObject: DetectedObject): Float? {
        // getKoreanClassName()으로 이미 변환된 이름이므로 영어 역매핑 후 조회
        val className = KOREAN_TO_ENGLISH[detectedObject.className] ?: detectedObject.className.lowercase()
        val boundingBox = detectedObject.boundingBox
        val category = detectedObject.category

        // Tier 0: Y좌표 근접 판단 (카메라 파라미터 무관, 해상도 독립적)
        val bottomYDistance = estimateByBottomY(boundingBox)
        if (bottomYDistance != null) {
            Log.d(TAG, "Distance by bottomY ($className): ${bottomYDistance}m")
            return bottomYDistance
        }

        // 방법 1: 참조 객체 방법 시도
        val knownHeight = getKnownObjectHeight(className, category)
        if (knownHeight != null) {
            val distance = estimateByReferenceObject(boundingBox, knownHeight)
            if (distance != null && distance > 0 && distance < MAX_RELIABLE_DISTANCE) {
                Log.d(TAG, "Distance by reference object ($className): ${distance}m")
                return distance
            }
        }

        // 방법 2: 지면 평면 가정 방법
        val groundDistance = estimateByGroundPlane(boundingBox)
        if (groundDistance != null && groundDistance > 0 && groundDistance < MAX_RELIABLE_DISTANCE) {
            Log.d(TAG, "Distance by ground plane: ${groundDistance}m")
            return groundDistance
        }

        // 방법 3: 바운딩 박스 크기 기반 휴리스틱
        val heuristicDistance = estimateByHeuristic(boundingBox, category)
        Log.d(TAG, "Distance by heuristic: ${heuristicDistance}m")

        return heuristicDistance
    }

    /**
     * Tier 0: Y좌표 기반 근접 판단.
     *
     * 화면 하단 = 발 앞 = 가깝다는 물리적 직관을 활용합니다.
     * 비율 기반이므로 해상도와 카메라 파라미터에 무관하게 동작합니다.
     */
    private fun estimateByBottomY(boundingBox: RectF): Float? {
        val bottomRatio = boundingBox.bottom / frameHeight
        return when {
            bottomRatio > 0.85f -> 1.0f  // 즉시 위험
            bottomRatio > 0.75f -> 2.0f  // 근접 경고
            else -> null                  // 다음 Tier로 위임
        }
    }

    /**
     * 참조 객체 방법으로 거리를 추정합니다.
     *
     * 원리: 알려진 실제 높이(H)와 이미지에서의 높이(h)를 사용
     * Distance = (H * focal_length) / h
     */
    private fun estimateByReferenceObject(
        boundingBox: RectF,
        realHeightMeters: Float
    ): Float? {
        // 바운딩 박스 높이 (픽셀)
        val boxHeightPx = boundingBox.height()

        if (boxHeightPx < MIN_BOX_HEIGHT_PX) {
            return null // 박스가 너무 작아서 신뢰할 수 없음
        }

        // 거리 계산: D = (H_real * f) / h_pixel
        val distance = (realHeightMeters * cameraFocalLengthPx) / boxHeightPx

        return if (distance > 0 && distance < MAX_RELIABLE_DISTANCE) distance else null
    }

    /**
     * 지면 평면 가정으로 거리를 추정합니다.
     *
     * 원리: 객체의 바닥이 지면에 닿아있다고 가정하고,
     * 바운딩 박스 하단의 이미지 좌표에서 거리를 계산합니다.
     */
    private fun estimateByGroundPlane(boundingBox: RectF): Float? {
        // 바운딩 박스 하단의 y 좌표 (이미지 상단 = 0)
        val boxBottomY = boundingBox.bottom

        // 이미지 중심으로부터의 오프셋 (픽셀)
        val centerY = frameHeight / 2f
        val offsetY = boxBottomY - centerY

        // 수직 각도 계산
        val anglePerPixel = VERTICAL_FOV_DEGREES / frameHeight
        val angleFromCenter = offsetY * anglePerPixel

        // 카메라 기울기 고려
        val totalAngle = cameraPitchDegrees + angleFromCenter

        // 각도가 0 이하면 지면 아래를 보지 않음
        if (totalAngle <= 0) {
            return null
        }

        // 삼각법으로 거리 계산
        val angleRad = Math.toRadians(totalAngle.toDouble())
        val distance = (cameraHeightMeters / tan(angleRad)).toFloat()

        return if (distance > 0 && distance < MAX_RELIABLE_DISTANCE) distance else null
    }

    /**
     * 바운딩 박스 크기 기반 휴리스틱으로 거리를 추정합니다.
     *
     * 객체 종류에 따른 평균 크기와 바운딩 박스 비율을 사용합니다.
     */
    private fun estimateByHeuristic(
        boundingBox: RectF,
        category: ObjectCategory
    ): Float? {
        val boxArea = boundingBox.width() * boundingBox.height()
        val frameArea = frameWidth * frameHeight

        // 박스가 프레임에서 차지하는 비율
        val areaRatio = boxArea / frameArea

        // 카테고리별 기준 거리 (비율 10%일 때의 추정 거리)
        val baseDistance = when (category) {
            ObjectCategory.MOVING_OBSTACLE -> 3f  // 사람 등
            ObjectCategory.STATIC_OBSTACLE -> 2f  // 기둥, 벤치 등
            ObjectCategory.HAZARD -> 2f           // 계단, 구덩이 등
            ObjectCategory.TRAFFIC_SIGNAL -> 5f   // 신호등 등
            ObjectCategory.LANDMARK -> 10f        // 건물 등
            ObjectCategory.UNKNOWN -> 3f
        }

        // 비율에 따라 거리 추정 (역제곱 관계 근사)
        // areaRatio가 크면 가까이, 작으면 멀리
        val referenceRatio = 0.1f
        val distance = baseDistance * kotlin.math.sqrt(referenceRatio / areaRatio)

        return distance.coerceIn(0.5f, MAX_RELIABLE_DISTANCE)
    }

    /**
     * 알려진 객체의 실제 높이를 반환합니다.
     */
    private fun getKnownObjectHeight(className: String, category: ObjectCategory): Float? {
        // 일반적인 객체들의 실제 높이 (미터)
        return when (className) {
            // 사람
            "person" -> PERSON_HEIGHT_METERS
            "child" -> CHILD_HEIGHT_METERS

            // 교통 관련
            "traffic light" -> TRAFFIC_LIGHT_HEIGHT_METERS
            "stop sign" -> STOP_SIGN_HEIGHT_METERS
            "traffic cone" -> TRAFFIC_CONE_HEIGHT_METERS

            // 도시 시설물
            "fire hydrant" -> FIRE_HYDRANT_HEIGHT_METERS
            "parking meter" -> PARKING_METER_HEIGHT_METERS
            "bench" -> BENCH_HEIGHT_METERS
            "bollard" -> BOLLARD_HEIGHT_METERS

            // 차량
            "car" -> CAR_HEIGHT_METERS
            "bus" -> BUS_HEIGHT_METERS
            "truck" -> TRUCK_HEIGHT_METERS
            "motorcycle" -> MOTORCYCLE_HEIGHT_METERS
            "bicycle" -> BICYCLE_HEIGHT_METERS

            // 카테고리 기반 기본값
            else -> when (category) {
                ObjectCategory.STATIC_OBSTACLE -> 1.0f
                ObjectCategory.MOVING_OBSTACLE -> 1.7f
                ObjectCategory.TRAFFIC_SIGNAL -> 2.5f
                else -> null
            }
        }
    }

    /**
     * 객체의 상대적 방향을 계산합니다.
     */
    fun calculateRelativeDirection(boundingBox: RectF): com.navblind.domain.model.RelativeDirection {
        val boxCenterX = boundingBox.centerX()
        val frameCenterX = frameWidth / 2f

        val offsetRatio = (boxCenterX - frameCenterX) / (frameWidth / 2f)

        return when {
            offsetRatio < -0.5f -> com.navblind.domain.model.RelativeDirection.LEFT
            offsetRatio < -0.15f -> com.navblind.domain.model.RelativeDirection.SLIGHTLY_LEFT
            offsetRatio > 0.5f -> com.navblind.domain.model.RelativeDirection.RIGHT
            offsetRatio > 0.15f -> com.navblind.domain.model.RelativeDirection.SLIGHTLY_RIGHT
            else -> com.navblind.domain.model.RelativeDirection.CENTER
        }
    }

    /**
     * 객체 목록에 거리와 방향 정보를 추가합니다.
     */
    fun enrichWithDistanceInfo(objects: List<DetectedObject>): List<DetectedObject> {
        return objects.map { obj ->
            val distance = estimateDistance(obj)
            val direction = calculateRelativeDirection(obj.boundingBox)

            obj.copy(
                estimatedDistance = distance,
                relativeDirection = direction,
                dangerLevel = calculateDangerLevel(obj, distance)
            )
        }
    }

    /**
     * 위험도를 계산합니다 (0-1).
     */
    private fun calculateDangerLevel(obj: DetectedObject, distance: Float?): Float {
        if (distance == null) return 0.3f // 거리 불명시 중간 위험도

        // 거리 기반 위험도 (가까울수록 위험)
        val distanceFactor = when {
            distance < 1f -> 1.0f
            distance < 2f -> 0.8f
            distance < 3f -> 0.6f
            distance < 5f -> 0.4f
            else -> 0.2f
        }

        // 카테고리 기반 가중치
        val categoryWeight = when (obj.category) {
            ObjectCategory.HAZARD -> 1.2f           // 위험 요소는 더 위험
            ObjectCategory.MOVING_OBSTACLE -> 1.1f  // 이동 장애물은 약간 더 위험
            ObjectCategory.STATIC_OBSTACLE -> 1.0f
            ObjectCategory.TRAFFIC_SIGNAL -> 0.5f   // 신호등은 덜 위험
            ObjectCategory.LANDMARK -> 0.3f         // 랜드마크는 거의 위험하지 않음
            ObjectCategory.UNKNOWN -> 0.8f
        }

        // 위치 기반 가중치 (중앙이 더 위험)
        val positionWeight = when (obj.relativeDirection) {
            com.navblind.domain.model.RelativeDirection.CENTER -> 1.2f
            com.navblind.domain.model.RelativeDirection.SLIGHTLY_LEFT,
            com.navblind.domain.model.RelativeDirection.SLIGHTLY_RIGHT -> 1.0f
            com.navblind.domain.model.RelativeDirection.LEFT,
            com.navblind.domain.model.RelativeDirection.RIGHT -> 0.7f
        }

        return (distanceFactor * categoryWeight * positionWeight).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "DistanceEstimator"

        // 스마트폰 카메라 파라미터 (USE_LOCAL_CAMERA=true, 손에 들고 촬영)
        private const val FOCAL_LENGTH_PHONE_PX = 1200f
        private const val CAMERA_HEIGHT_PHONE_METERS = 1.4f
        private const val CAMERA_PITCH_PHONE_DEGREES = -15f // 약간 내려보는 각도

        // 스마트글래스 ESP32-CAM 파라미터 (USE_LOCAL_CAMERA=false)
        private const val FOCAL_LENGTH_GLASS_PX = 800f
        private const val CAMERA_HEIGHT_GLASS_METERS = 1.6f // 안경 착용 높이
        private const val CAMERA_PITCH_GLASS_DEGREES = -5f  // 수평에 가까운 각도

        private const val VERTICAL_FOV_DEGREES = 60f // 수직 시야각 (공통)

        // 거리 추정 제한
        private const val MAX_RELIABLE_DISTANCE = 15f // 미터 (heuristic 20m 고착 방지를 위해 15m로 낮춤)
        private const val MIN_BOX_HEIGHT_PX = 10f // 최소 바운딩 박스 높이 (작은 박스도 허용)

        // YoloObjectDetector에서 한국어로 변환된 클래스명을 역매핑
        internal val KOREAN_TO_ENGLISH = mapOf(
            "사람" to "person",
            "자전거" to "bicycle",
            "자동차" to "car",
            "오토바이" to "motorcycle",
            "버스" to "bus",
            "트럭" to "truck",
            "신호등" to "traffic light",
            "소화전" to "fire hydrant",
            "정지 표지판" to "stop sign",
            "주차 미터기" to "parking meter",
            "벤치" to "bench",
            "라바콘" to "traffic cone",
            "볼라드" to "bollard"
        )

        // 알려진 객체 높이 (미터)
        private const val PERSON_HEIGHT_METERS = 1.7f
        private const val CHILD_HEIGHT_METERS = 1.2f
        private const val TRAFFIC_LIGHT_HEIGHT_METERS = 0.4f // 신호등 박스 부분
        private const val STOP_SIGN_HEIGHT_METERS = 0.6f
        private const val TRAFFIC_CONE_HEIGHT_METERS = 0.7f
        private const val FIRE_HYDRANT_HEIGHT_METERS = 0.6f
        private const val PARKING_METER_HEIGHT_METERS = 1.2f
        private const val BENCH_HEIGHT_METERS = 0.8f
        private const val BOLLARD_HEIGHT_METERS = 1.0f
        private const val CAR_HEIGHT_METERS = 1.5f
        private const val BUS_HEIGHT_METERS = 3.0f
        private const val TRUCK_HEIGHT_METERS = 3.5f
        private const val MOTORCYCLE_HEIGHT_METERS = 1.2f
        private const val BICYCLE_HEIGHT_METERS = 1.1f
    }
}
