package com.navblind.service.recording

import com.navblind.domain.model.FusedPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.time.Instant

/**
 * FusedPosition 스트림을 GPX 파일로 기록한다.
 *
 * 사용법:
 *   val recorder = GpxRecorder(File(sessionDir, "route.gpx"))
 *   recorder.start(locationFusionService.fusedPosition)
 *   // ... 걷기 ...
 *   recorder.stop()  // -> route.gpx 완성
 *
 * 결과물은 Android Studio 에뮬레이터 Extended Controls > Location > Routes 에서
 * Import GPX 로 바로 불러올 수 있다.
 */
class GpxRecorder(private val outputFile: File) {

    private var writer: BufferedWriter? = null
    private var job: Job? = null
    private var pointCount = 0

    fun start(positionFlow: StateFlow<FusedPosition?>) {
        outputFile.parentFile?.mkdirs()
        writer = outputFile.bufferedWriter().also { w ->
            w.write(GPX_HEADER)
            w.flush()
        }

        job = CoroutineScope(Dispatchers.IO).launch {
            positionFlow.collect { pos ->
                pos ?: return@collect
                appendPoint(pos)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        writer?.apply {
            write(GPX_FOOTER)
            flush()
            close()
        }
        writer = null
    }

    val recordedPoints: Int get() = pointCount

    private fun appendPoint(pos: FusedPosition) {
        val lat = pos.coordinate.latitude
        val lon = pos.coordinate.longitude
        val alt = pos.altitude ?: 0.0
        val time = Instant.ofEpochMilli(pos.timestamp)

        writer?.run {
            write(
                """  <trkpt lat="$lat" lon="$lon">
    <ele>$alt</ele>
    <time>$time</time>
  </trkpt>
"""
            )
            flush()
        }
        pointCount++
    }

    companion object {
        private val GPX_HEADER = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="NavBlind-DataCollector"
     xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>NavBlind Test Session</name><trkseg>
"""
        private val GPX_FOOTER = "</trkseg></trk></gpx>\n"
    }
}
