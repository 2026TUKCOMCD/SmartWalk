package com.navblind.service.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.navblind.domain.model.DetectedObject
import com.navblind.domain.model.ObjectCategory
import com.navblind.domain.model.ObjectDetectionResult
import com.navblind.domain.model.RelativeDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * YOLOv8n TFLite 모델을 사용한 객체 검출기입니다.
 *
 * 주요 기능:
 * - ESP32-CAM 스트림에서 받은 프레임에서 장애물 검출
 * - GPU 가속 지원 (가능한 경우)
 * - NMS(Non-Maximum Suppression)로 중복 검출 제거
 * - DistanceEstimator와 연동하여 거리 추정
 */
@Singleton
class YoloObjectDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val distanceEstimator: DistanceEstimator
) {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private var isInitialized = false
    private var inputWidth = MODEL_INPUT_SIZE
    private var inputHeight = MODEL_INPUT_SIZE

    // 라벨 목록 (COCO 80 클래스 + 커스텀)
    private val labels = mutableListOf<String>()

    /**
     * 모델을 초기화합니다.
     * 앱 시작 시 또는 카메라 연결 전에 호출해야 합니다.
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
            // 라벨 로드
            loadLabels()

            // 모델 로드
            val modelBuffer = loadModelFile(MODEL_FILE_NAME)

            // GPU 가속 설정 (불가 시 CPU fallback)
            val options = Interpreter.Options()
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                Log.d(TAG, "GPU delegate enabled")
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate not available, using CPU: ${t.message}")
                gpuDelegate = null
                options.setNumThreads(4)
            }

            interpreter = Interpreter(modelBuffer, options)

            // 입력 텐서 크기 확인
            val inputShape = interpreter?.getInputTensor(0)?.shape()
            if (inputShape != null && inputShape.size >= 4) {
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }

            distanceEstimator.setFrameSize(inputWidth, inputHeight)

            isInitialized = true
            Log.d(TAG, "YOLOv8n initialized: ${inputWidth}x${inputHeight}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize YOLOv8n", e)
            false
        }
    }

    /**
     * 비트맵에서 객체를 검출합니다.
     *
     * @param bitmap 입력 이미지
     * @return 검출된 객체 목록
     */
    fun detect(bitmap: Bitmap): ObjectDetectionResult {
        if (!isInitialized) {
            Log.w(TAG, "Detector not initialized")
            return ObjectDetectionResult(
                objects = emptyList(),
                frameTimestamp = System.currentTimeMillis(),
                inferenceTimeMs = 0,
                frameWidth = bitmap.width,
                frameHeight = bitmap.height
            )
        }

        val startTime = System.currentTimeMillis()

        // 1. 전처리: 리사이즈 및 정규화
        val inputBuffer = preprocessImage(bitmap)

        // 2. 추론
        val outputBuffer = runInference(inputBuffer)

        // 3. 후처리: NMS 적용
        val rawDetections = postprocessOutput(outputBuffer, bitmap.width, bitmap.height)

        // 4. 거리 추정 및 위험도 계산
        distanceEstimator.setFrameSize(bitmap.width, bitmap.height)
        val enrichedDetections = distanceEstimator.enrichWithDistanceInfo(rawDetections)

        val inferenceTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "Detection completed in ${inferenceTime}ms, found ${enrichedDetections.size} objects")

        return ObjectDetectionResult(
            objects = enrichedDetections,
            frameTimestamp = System.currentTimeMillis(),
            inferenceTimeMs = inferenceTime,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height
        )
    }

    /**
     * 이미지를 모델 입력 형식으로 전처리합니다.
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val pixels = IntArray(inputWidth * inputHeight)
        resizedBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        // YOLO는 RGB 순서, 0-1 정규화
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        return inputBuffer
    }

    /**
     * 모델 추론을 실행합니다.
     */
    private fun runInference(inputBuffer: ByteBuffer): Array<Array<FloatArray>> {
        // YOLOv8 출력: [1, 84, 8400] (80 클래스 + 4 좌표)
        // 또는 [1, num_classes+4, num_detections]
        val numDetections = 8400
        val numClasses = labels.size
        val outputSize = numClasses + 4

        val output = Array(1) { Array(outputSize) { FloatArray(numDetections) } }

        inputBuffer.rewind()
        interpreter?.run(inputBuffer, output)

        return output
    }

    /**
     * 모델 출력을 후처리하여 검출된 객체 목록으로 변환합니다.
     */
    private fun postprocessOutput(
        output: Array<Array<FloatArray>>,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectedObject> {
        val detections = mutableListOf<RawDetection>()
        val numClasses = labels.size
        val numDetections = output[0][0].size

        // 출력 파싱 (YOLOv8 형식: [1, 84, 8400])
        for (i in 0 until numDetections) {
            // 좌표 (cx, cy, w, h)
            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]

            // 클래스 확률 중 최대값 찾기
            var maxClassProb = 0f
            var maxClassId = 0
            for (c in 0 until minOf(numClasses, 80)) {
                val prob = output[0][4 + c][i]
                if (prob > maxClassProb) {
                    maxClassProb = prob
                    maxClassId = c
                }
            }

            // 신뢰도 임계값 체크
            if (maxClassProb >= CONFIDENCE_THRESHOLD) {
                // 좌표를 원본 이미지 크기로 변환
                val scaleX = originalWidth.toFloat() / inputWidth
                val scaleY = originalHeight.toFloat() / inputHeight

                val left = (cx - w / 2) * scaleX
                val top = (cy - h / 2) * scaleY
                val right = (cx + w / 2) * scaleX
                val bottom = (cy + h / 2) * scaleY

                detections.add(RawDetection(
                    boundingBox = RectF(
                        max(0f, left),
                        max(0f, top),
                        min(originalWidth.toFloat(), right),
                        min(originalHeight.toFloat(), bottom)
                    ),
                    classId = maxClassId,
                    confidence = maxClassProb
                ))
            }
        }

        // NMS 적용
        val nmsDetections = applyNMS(detections)

        // DetectedObject로 변환
        return nmsDetections.mapIndexed { index, detection ->
            val className = if (detection.classId < labels.size) {
                labels[detection.classId]
            } else {
                "unknown"
            }

            DetectedObject(
                id = index,
                classId = detection.classId,
                className = getKoreanClassName(className),
                confidence = detection.confidence,
                boundingBox = detection.boundingBox,
                category = ObjectCategory.fromClassName(className)
            )
        }
    }

    /**
     * Non-Maximum Suppression을 적용하여 중복 검출을 제거합니다.
     */
    private fun applyNMS(detections: List<RawDetection>): List<RawDetection> {
        if (detections.isEmpty()) return emptyList()

        // 신뢰도 순으로 정렬
        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<RawDetection>()
        val suppressed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (suppressed[i]) continue

            selected.add(sorted[i])

            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue

                val iou = calculateIoU(sorted[i].boundingBox, sorted[j].boundingBox)
                if (iou >= NMS_THRESHOLD) {
                    suppressed[j] = true
                }
            }
        }

        return selected.take(MAX_DETECTIONS)
    }

    /**
     * 두 바운딩 박스의 IoU(Intersection over Union)를 계산합니다.
     */
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectLeft = max(box1.left, box2.left)
        val intersectTop = max(box1.top, box2.top)
        val intersectRight = min(box1.right, box2.right)
        val intersectBottom = min(box1.bottom, box2.bottom)

        if (intersectLeft >= intersectRight || intersectTop >= intersectBottom) {
            return 0f
        }

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectArea

        return if (unionArea > 0) intersectArea / unionArea else 0f
    }

    /**
     * 모델 파일을 로드합니다.
     */
    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val assetManager = context.assets
        val fileDescriptor = assetManager.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * 라벨 파일을 로드합니다.
     */
    private fun loadLabels() {
        labels.clear()
        try {
            context.assets.open(LABELS_FILE_NAME).bufferedReader().useLines { lines ->
                lines.forEach { labels.add(it.trim()) }
            }
            Log.d(TAG, "Loaded ${labels.size} labels")
        } catch (e: Exception) {
            Log.w(TAG, "Labels file not found, using COCO defaults")
            labels.addAll(COCO_LABELS)
        }
    }

    /**
     * 영어 클래스명을 한국어로 변환합니다.
     */
    private fun getKoreanClassName(englishName: String): String {
        return KOREAN_LABELS[englishName.lowercase()] ?: englishName
    }

    /**
     * 리소스를 해제합니다.
     */
    fun release() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        isInitialized = false
        Log.d(TAG, "YOLOv8n released")
    }

    private data class RawDetection(
        val boundingBox: RectF,
        val classId: Int,
        val confidence: Float
    )

    companion object {
        private const val TAG = "YoloObjectDetector"
        private const val MODEL_FILE_NAME = "yolov8n.tflite"
        private const val LABELS_FILE_NAME = "labels.txt"
        private const val MODEL_INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.40f
        private const val NMS_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 20

        // COCO 80 클래스 기본 라벨
        private val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )

        // 한국어 라벨 매핑 (주요 장애물 위주)
        private val KOREAN_LABELS = mapOf(
            "person" to "사람",
            "bicycle" to "자전거",
            "car" to "자동차",
            "motorcycle" to "오토바이",
            "bus" to "버스",
            "truck" to "트럭",
            "traffic light" to "신호등",
            "fire hydrant" to "소화전",
            "stop sign" to "정지 표지판",
            "parking meter" to "주차 미터기",
            "bench" to "벤치",
            "bird" to "새",
            "cat" to "고양이",
            "dog" to "개",
            "backpack" to "배낭",
            "umbrella" to "우산",
            "chair" to "의자",
            "couch" to "소파",
            "potted plant" to "화분",
            "bed" to "침대",
            "dining table" to "테이블",
            "tv" to "TV",
            "laptop" to "노트북",
            "cell phone" to "휴대폰",
            "bottle" to "병",
            "cup" to "컵",
            // 커스텀 추가 (나중에 훈련 시)
            "traffic cone" to "라바콘",
            "bollard" to "볼라드",
            "braille block" to "점자블록",
            "construction sign" to "공사 표지판",
            "kickboard" to "전동킥보드",
            "manhole" to "맨홀"
        )
    }
}
