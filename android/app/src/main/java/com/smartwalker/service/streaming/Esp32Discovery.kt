package com.smartwalker.service.streaming

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 같은 와이파이(폰 핫스팟) 대역에서 ESP32-CAM MJPEG 스트림을 자동으로 찾습니다.
 *
 * 배경: ESP32 펌웨어가 IP를 고정하지 않고 핫스팟 DHCP에서 받은 IP를 사용하므로
 * 앱이 주소를 미리 알 수 없습니다. mDNS도 없어 빌드에 박힌 고정 URL은 맞지 않습니다.
 * 그래서 런타임에 로컬 서브넷을 스캔해 동작하는 스트림 URL을 탐색합니다.
 *
 * 스트림 주소 형식이 불확실하므로 호스트마다 두 형식을 모두 시도합니다:
 *   - http://<IP>/stream       (포트 80)
 *   - http://<IP>:81/stream    (ESP32-CAM 표준 예제 기본 포트)
 *
 * 판정: GET 응답의 Content-Type 이 multipart 이면 MJPEG 스트림으로 간주합니다
 * (펌웨어 종류와 무관하게 동작).
 */
@Singleton
class Esp32Discovery @Inject constructor() {

    // 탐색 전용 클라이언트: 짧은 타임아웃으로 빠르게 다수 호스트를 훑는다.
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // 마지막으로 탐색에 성공한 URL. 풀 서브넷 스캔(254개×2포트)보다 먼저 한 번만 찔러봐
    // 재탐색을 거의 즉시 끝낸다. 폰을 바꿔 IP가 달라지면 probe가 실패해 자연히 스캔으로 넘어간다.
    @Volatile private var lastWorkingUrl: String? = null

    /**
     * 동작하는 MJPEG 스트림 URL을 반환합니다. 찾지 못하면 null.
     *
     * 순서: ① 직전 성공 URL → ② 설정(빌드/.env) URL → ③ 서브넷 스캔.
     *
     * @param preferredUrl 빌드에 설정된 URL. 비어있지 않으면 확인 후 실패 시 스캔으로 넘어갑니다.
     */
    suspend fun resolve(preferredUrl: String?): String? {
        lastWorkingUrl?.let {
            if (probe(it)) {
                Log.d(TAG, "직전 성공 URL 재사용: $it")
                return it
            }
            Log.d(TAG, "직전 URL($it) 응답 없음 → 설정 URL/스캔으로")
        }
        if (!preferredUrl.isNullOrBlank() && probe(preferredUrl)) {
            Log.d(TAG, "설정된 URL 사용: $preferredUrl")
            lastWorkingUrl = preferredUrl
            return preferredUrl
        }
        if (!preferredUrl.isNullOrBlank()) {
            Log.d(TAG, "설정된 URL($preferredUrl) 응답 없음 → 자동 탐색 시작")
        }
        return scan()?.also { lastWorkingUrl = it }
    }

    /** 로컬 서브넷 전체(/24)를 동시 스캔해 첫 번째로 응답하는 스트림 URL을 찾는다. */
    private suspend fun scan(): String? = coroutineScope {
        val prefixes = localSubnetPrefixes()
        if (prefixes.isEmpty()) {
            Log.w(TAG, "로컬 서브넷을 찾지 못함 — 핫스팟이 켜져 있는지 확인 필요")
            return@coroutineScope null
        }
        Log.d(TAG, "ESP32 스캔 대상 서브넷: ${prefixes.map { "${it}0/24" }}")

        val semaphore = Semaphore(MAX_CONCURRENCY)
        val result = CompletableDeferred<String?>()

        val jobs = prefixes.flatMap { prefix ->
            (1..254).map { host ->
                launch {
                    if (result.isCompleted) return@launch
                    semaphore.withPermit {
                        if (result.isCompleted) return@withPermit
                        probeHost("$prefix$host")?.let { url ->
                            if (!result.isCompleted) result.complete(url)
                        }
                    }
                }
            }
        }

        // 모든 호스트를 다 훑었는데 못 찾으면 null로 종료
        launch {
            jobs.joinAll()
            if (!result.isCompleted) result.complete(null)
        }

        val found = result.await()
        coroutineContext.cancelChildren()  // 남은 스캔 잡 정리
        if (found != null) Log.i(TAG, "ESP32 스트림 발견: $found")
        else Log.w(TAG, "서브넷 스캔 완료 — ESP32 스트림을 찾지 못함")
        found
    }

    /** 한 호스트에 대해 두 URL 형식을 순서대로 시도. */
    private suspend fun probeHost(host: String): String? {
        for (url in candidateUrls(host)) {
            if (!currentCoroutineContext().isActive) return null
            if (probe(url)) return url
        }
        return null
    }

    private fun candidateUrls(host: String) = listOf(
        "http://$host/stream",
        "http://$host:$ALT_PORT/stream"
    )

    /**
     * GET 후 Content-Type 이 multipart 면 true. 본문은 읽지 않고 바로 닫는다.
     * 연결 거부/타임아웃 등은 모두 false 로 처리.
     */
    private suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            probeClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                response.isSuccessful && contentType.contains("multipart")
            }
        } catch (_: Exception) {
            false
        }
    }

    /** UP 상태의 비루프백 인터페이스에서 사이트로컬 IPv4의 /24 프리픽스 목록 (예: "192.168.43."). */
    private fun localSubnetPrefixes(): List<String> {
        val prefixes = linkedSetOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        val lastDot = ip.lastIndexOf('.')
                        if (lastDot > 0) prefixes.add(ip.substring(0, lastDot + 1))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "로컬 서브넷 조회 실패", e)
        }
        return prefixes.toList()
    }

    companion object {
        private const val TAG = "Esp32Discovery"
        private const val PROBE_TIMEOUT_MS = 400L
        private const val MAX_CONCURRENCY = 64
        private const val ALT_PORT = 81
    }
}
