package com.smartwalker.service.location

import android.content.Context
import android.util.Log
import com.smartwalker.domain.model.FusedPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 디버그/분석용 트랙 레코더.
 *
 * 보행 중 **원본 GPS**(KF 보정 전)와 **보정 위치**(KF·게이팅·맵매칭 후)를 각각
 * 수집하여, 종료 시 하나의 GeoJSON 파일에 두 개의 LineString(원본=빨강, 보정=파랑)으로
 * 저장한다. 파일을 adb로 빼서 geojson.io·kepler.gl 등에 드롭하면 두 동선이 겹쳐 보인다.
 *
 * 저장 위치: `<외부앱전용>/files/tracks/track_yyyyMMdd_HHmmss.geojson`
 *   adb pull 예시:
 *   adb pull /sdcard/Android/data/com.smartwalker/files/tracks ./tracks
 *
 * 순환 의존을 피하려고 LocationFusionService가 자신의 Flow를 인자로 넘겨 start()를 호출한다.
 */
@Singleton
class TrackRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    private val rawPoints = mutableListOf<TrackPoint>()
    private val fusedPoints = mutableListOf<TrackPoint>()
    private val jobs = mutableListOf<Job>()

    private var outFile: File? = null
    @Volatile private var recording = false

    /**
     * 기록을 시작한다. 이미 기록 중이면 무시한다.
     *
     * @param gpsFlow   원본 GPS StateFlow (LocationFusionService.gpsPosition)
     * @param fusedFlow 보정 위치 StateFlow (LocationFusionService.fusedPosition)
     */
    fun start(
        gpsFlow: StateFlow<FusedPosition?>,
        fusedFlow: StateFlow<FusedPosition?>
    ) {
        if (recording) return
        recording = true
        synchronized(lock) {
            rawPoints.clear()
            fusedPoints.clear()
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
        outFile = File(dir, "track_$stamp.geojson")

        jobs += scope.launch {
            gpsFlow.filterNotNull().collect { append(rawPoints, it) }
        }
        jobs += scope.launch {
            fusedFlow.filterNotNull().collect { append(fusedPoints, it) }
        }
        Log.i(TAG, "트랙 기록 시작 → ${outFile?.absolutePath}")
    }

    /** 기록을 종료하고 최종 파일을 쓴다. */
    fun stop() {
        if (!recording) return
        recording = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        synchronized(lock) { writeFile() }
        Log.i(TAG, "트랙 저장 완료: ${outFile?.absolutePath} (raw=${rawPoints.size}, fused=${fusedPoints.size})")
    }

    private fun append(list: MutableList<TrackPoint>, p: FusedPosition) {
        synchronized(lock) {
            list += TrackPoint(
                lat = p.coordinate.latitude,
                lng = p.coordinate.longitude,
                timestamp = p.timestamp,
                accuracy = p.accuracy
            )
            // 앱이 갑자기 종료돼도 손실을 줄이기 위해 주기적으로 디스크에 반영.
            if ((rawPoints.size + fusedPoints.size) % FLUSH_EVERY == 0) writeFile()
        }
    }

    /** lock을 잡은 상태에서 호출할 것. 전체 GeoJSON을 매번 새로 쓴다(트랙 길이 작음). */
    private fun writeFile() {
        val f = outFile ?: return
        try {
            f.writeText(buildGeoJson())
        } catch (e: Exception) {
            Log.e(TAG, "트랙 파일 쓰기 실패", e)
        }
    }

    private fun buildGeoJson(): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"features\":[")
        append(feature("raw_gps", "#ff3030", rawPoints))
        append(",")
        append(feature("fused", "#2060ff", fusedPoints))
        append("]}")
    }

    /** 한 트랙을 simplestyle(stroke) 속성이 붙은 LineString(또는 점 1개면 Point) Feature로 만든다. */
    private fun feature(name: String, color: String, pts: List<TrackPoint>): String = buildString {
        append("{\"type\":\"Feature\",\"properties\":{")
        append("\"name\":\"").append(name).append("\",")
        append("\"stroke\":\"").append(color).append("\",")
        append("\"stroke-width\":3,")
        append("\"count\":").append(pts.size).append(",")
        append("\"times\":[")
        pts.forEachIndexed { i, p ->
            if (i > 0) append(",")
            append(p.timestamp)
        }
        append("]},\"geometry\":")
        when {
            pts.size >= 2 -> {
                append("{\"type\":\"LineString\",\"coordinates\":[")
                pts.forEachIndexed { i, p ->
                    if (i > 0) append(",")
                    append(coord(p))
                }
                append("]}")
            }
            pts.size == 1 -> append("{\"type\":\"Point\",\"coordinates\":").append(coord(pts[0])).append("}")
            else -> append("null")
        }
        append("}")
    }

    /** GeoJSON 좌표 순서는 [경도, 위도]. 과학표기/로케일 문제를 피하려 고정 포맷 사용. */
    private fun coord(p: TrackPoint): String =
        "[%.7f,%.7f]".format(Locale.US, p.lng, p.lat)

    private data class TrackPoint(
        val lat: Double,
        val lng: Double,
        val timestamp: Long,
        val accuracy: Float
    )

    companion object {
        private const val TAG = "TrackRecorder"
        private const val FLUSH_EVERY = 20
    }
}
