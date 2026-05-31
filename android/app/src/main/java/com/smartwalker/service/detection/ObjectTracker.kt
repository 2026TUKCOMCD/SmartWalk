package com.smartwalker.service.detection

import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.smartwalker.domain.model.DetectedObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * IoU 기반 다중 프레임 객체 추적기.
 *
 * 프레임마다 새로운 YOLO 검출 결과를 받아 이전 트랙과 매칭하여
 * 안정적인 trackId와 바운딩박스 이력을 유지한다.
 * 이 이력은 TrajectoryPredictor가 속도·충돌위험도 계산에 사용한다.
 */
@Singleton
class ObjectTracker @Inject constructor() {

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 0

    /** 현재 활성 트랙 목록 (외부에서 읽기 전용) */
    val activeTracks: List<Track> get() = tracks.filter { it.missedFrames == 0 }

    /**
     * 새 프레임의 검출 결과를 트랙에 매칭·갱신하고
     * 안정적인 trackId가 반영된 DetectedObject 목록을 반환한다.
     */
    fun update(detections: List<DetectedObject>): List<DetectedObject> {
        if (detections.isEmpty()) {
            // 모든 트랙의 miss 카운트 증가 후 만료 제거
            tracks.forEach { it.missedFrames++ }
            tracks.removeAll { it.missedFrames > MAX_MISSED_FRAMES }
            return emptyList()
        }

        // IoU 행렬 계산
        val iouMatrix = Array(tracks.size) { t ->
            FloatArray(detections.size) { d ->
                calculateIoU(tracks[t].lastBox, detections[d].boundingBox)
            }
        }

        val matchedTracks = BooleanArray(tracks.size)
        val matchedDetections = BooleanArray(detections.size)
        val assignments = mutableMapOf<Int, Int>() // detectionIdx → trackIdx

        // 그리디 매칭 (IoU 내림차순)
        data class Pair(val iou: Float, val t: Int, val d: Int)
        val pairs = mutableListOf<Pair>()
        for (t in tracks.indices) {
            for (d in detections.indices) {
                if (iouMatrix[t][d] >= IOU_THRESHOLD) {
                    pairs.add(Pair(iouMatrix[t][d], t, d))
                }
            }
        }
        pairs.sortByDescending { it.iou }
        for ((_, t, d) in pairs) {
            if (!matchedTracks[t] && !matchedDetections[d]) {
                assignments[d] = t
                matchedTracks[t] = true
                matchedDetections[d] = true
            }
        }

        // 매칭된 트랙 갱신
        for ((dIdx, tIdx) in assignments) {
            tracks[tIdx].update(detections[dIdx].boundingBox)
        }

        // 미매칭 트랙: miss 카운트 증가
        for (t in tracks.indices) {
            if (!matchedTracks[t]) tracks[t].missedFrames++
        }

        // 만료 트랙 제거
        tracks.removeAll { it.missedFrames > MAX_MISSED_FRAMES }

        // 미매칭 검출: 새 트랙 생성
        for (d in detections.indices) {
            if (!matchedDetections[d]) {
                tracks.add(Track(nextTrackId++, detections[d].boundingBox))
            }
        }

        // 각 검출에 trackId 할당하여 반환
        return detections.mapIndexed { d, detection ->
            val trackId = assignments[d]?.let { tracks.getOrNull(it)?.id }
                ?: tracks.lastOrNull()?.id // 새로 생성된 트랙
                ?: detection.id
            detection.copy(id = trackId)
        }
    }

    /** 모든 트랙 초기화 */
    fun reset() {
        tracks.clear()
        nextTrackId = 0
        Log.d(TAG, "Tracker reset")
    }

    /**
     * 특정 trackId의 바운딩박스 이력을 반환한다.
     * TrajectoryPredictor가 속도 계산에 사용한다.
     */
    fun getHistory(trackId: Int): List<RectF> =
        tracks.find { it.id == trackId }?.history?.toList() ?: emptyList()

    /**
     * 특정 trackId의 속도 벡터를 반환한다 (픽셀/프레임).
     * history 길이가 2 미만이면 zero velocity.
     */
    fun getVelocity(trackId: Int): PointF {
        val history = getHistory(trackId)
        if (history.size < 2) return PointF(0f, 0f)
        val recent = history.takeLast(VELOCITY_WINDOW)
        val oldest = recent.first()
        val newest = recent.last()
        val frames = (recent.size - 1).coerceAtLeast(1)
        return PointF(
            (newest.centerX() - oldest.centerX()) / frames,
            (newest.centerY() - oldest.centerY()) / frames
        )
    }

    // ─── 내부 구조 ───────────────────────────────────────────────────────────

    data class Track(
        val id: Int,
        private val initialBox: RectF,
        var missedFrames: Int = 0,
        val history: ArrayDeque<RectF> = ArrayDeque(MAX_HISTORY)
    ) {
        val lastBox: RectF get() = history.last()
        val age: Int get() = history.size

        init {
            history.addLast(initialBox)
        }

        fun update(box: RectF) {
            missedFrames = 0
            if (history.size >= MAX_HISTORY) history.removeFirst()
            history.addLast(box)
        }
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        if (interLeft >= interRight || interTop >= interBottom) return 0f
        val intersection = (interRight - interLeft) * (interBottom - interTop)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0) intersection / union else 0f
    }

    companion object {
        private const val TAG = "ObjectTracker"
        private const val IOU_THRESHOLD = 0.3f
        private const val MAX_MISSED_FRAMES = 5
        private const val MAX_HISTORY = 10
        private const val VELOCITY_WINDOW = 3
    }
}
