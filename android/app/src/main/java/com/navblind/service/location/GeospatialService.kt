package com.navblind.service.location

import android.content.Context
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.navblind.BuildConfig
import com.navblind.domain.model.Coordinate
import com.navblind.domain.model.FusedPosition
import com.navblind.domain.model.PositionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ARCore Geospatial API (VPS) 서비스.
 *
 * 모드:
 *   IDLE      → 비활성
 *   LIVE      → 실제 카메라로 실시간 VPS (실외 기기)
 *   RECORDING → LIVE + .mp4 녹화 (실외 데이터 수집 중)
 *   PLAYBACK  → 녹화된 .mp4 재생으로 VPS 시뮬레이션 (에뮬레이터 테스트)
 *
 * ARCore session.update()는 EGL 컨텍스트가 필요하므로
 * 전용 단일 스레드(arCoreThread)에서만 실행합니다.
 *
 * 사용:
 *   startLive()                // 실시간 VPS (LocationFusionService)
 *   startPlayback(path)        // 녹화 재생 (에뮬레이터)
 *   startRecording(path)       // LIVE 중 녹화 시작 (DataCollectionService)
 *   stopRecording()            // 녹화 중지 (DataCollectionService)
 *   release()                  // 세션 종료
 */
@Singleton
class GeospatialService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class Mode { IDLE, LIVE, RECORDING, PLAYBACK }

    @Volatile private var session: Session? = null
    @Volatile private var earth: Earth? = null
    @Volatile var currentMode: Mode = Mode.IDLE
        private set

    private val _geospatialPosition = MutableStateFlow<FusedPosition?>(null)
    val geospatialPosition: StateFlow<FusedPosition?> = _geospatialPosition.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    // 전용 단일 스레드: EGL context affinity 보장
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val arCoreThread = newSingleThreadContext("ARCore-update")
    private val scope = CoroutineScope(arCoreThread + SupervisorJob())
    private var sessionJob: Job? = null

    // EGL 리소스 (arCoreThread에서만 접근)
    private var eglDisplay: android.opengl.EGLDisplay? = null
    private var eglCtx: android.opengl.EGLContext? = null
    private var eglSurface: android.opengl.EGLSurface? = null
    private var cameraTextureId: Int = -1

    // ── Public API ──────────────────────────────────────────────────────────

    fun checkAvailability(): Boolean = try {
        val avail = ArCoreApk.getInstance().checkAvailability(context)
        val ok = avail == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
                avail == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ||
                avail == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED
        Log.d(TAG, "ARCore availability: $avail, ok=$ok")
        ok
    } catch (e: Exception) {
        Log.e(TAG, "checkAvailability 오류", e)
        false
    }

    /**
     * 실시간 VPS 추적을 시작합니다.
     * 이미 LIVE/RECORDING 상태면 무시합니다.
     */
    fun startLive() {
        if (currentMode == Mode.LIVE || currentMode == Mode.RECORDING) {
            Log.d(TAG, "이미 LIVE/RECORDING 모드, startLive() 무시")
            return
        }
        launchSession(Mode.LIVE, datasetPath = null)
    }

    /**
     * 현재 실행 중인 ARCore 세션을 .mp4 파일로 녹화합니다.
     * startLive() 후, isAvailable == true 상태에서 호출하세요.
     */
    fun startRecording(outputPath: String) {
        val s = session ?: run {
            Log.w(TAG, "startRecording: 세션 없음 (아직 초기화 중?)")
            return
        }
        try {
            val config = RecordingConfig(s).setMp4DatasetUri(Uri.fromFile(File(outputPath)))
            s.startRecording(config)
            currentMode = Mode.RECORDING
            Log.i(TAG, "ARCore 녹화 시작: $outputPath")
        } catch (e: Exception) {
            Log.e(TAG, "ARCore 녹화 시작 실패", e)
        }
    }

    /**
     * 녹화를 중지합니다. VPS 추적은 LIVE 모드로 계속됩니다.
     */
    fun stopRecording() {
        try {
            session?.stopRecording()
            if (currentMode == Mode.RECORDING) currentMode = Mode.LIVE
            Log.i(TAG, "ARCore 녹화 중지")
        } catch (e: Exception) {
            Log.e(TAG, "ARCore 녹화 중지 실패", e)
        }
    }

    /**
     * 녹화된 .mp4를 재생하여 에뮬레이터에서 VPS를 테스트합니다.
     */
    fun startPlayback(datasetPath: String) {
        launchSession(Mode.PLAYBACK, datasetPath)
    }

    /**
     * 디버그 빌드에서 sessions/ 폴더의 최신 session.mp4를 자동 탐색합니다.
     * 없으면 null 반환.
     */
    fun findLatestDatasetFile(): String? {
        if (!BuildConfig.DEBUG) return null
        val sessionsDir = File(context.getExternalFilesDir(null), "sessions")
        val found = sessionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name.toLongOrNull() ?: 0L }
            ?.firstNotNullOfOrNull { dir ->
                File(dir, "session.mp4").takeIf { it.exists() }
            }
            ?.absolutePath
        Log.d(TAG, if (found != null) "데이터셋 파일 발견: $found" else "데이터셋 파일 없음")
        return found
    }

    /**
     * 세션을 중지하고 모든 리소스를 해제합니다.
     */
    fun release() {
        sessionJob?.cancel()
        sessionJob = null
        currentMode = Mode.IDLE
        _isAvailable.value = false
        _geospatialPosition.value = null
        Log.d(TAG, "Geospatial service released")
    }

    // ── 내부 구현 ────────────────────────────────────────────────────────────

    private fun launchSession(mode: Mode, datasetPath: String?) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            runSession(mode, datasetPath)
        }
    }

    /**
     * arCoreThread에서 실행되는 세션 메인 루프.
     * EGL 설정 → 세션 생성/재개 → update 루프 → 정리
     */
    private suspend fun runSession(mode: Mode, datasetPath: String?) {
        if (!checkAvailability()) {
            Log.w(TAG, "ARCore 사용 불가, 세션 취소")
            return
        }

        // 1. EGL headless context 설정 (arCoreThread에서)
        if (!setupEgl()) {
            Log.e(TAG, "EGL 설정 실패, 세션 취소")
            return
        }

        var localSession: Session? = null
        try {
            // 2. ARCore 카메라 텍스처 생성 (EGL context 필요)
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0])
            cameraTextureId = texIds[0]

            // 3. ARCore 세션 생성 및 설정
            localSession = Session(context).apply {
                val cfg = config
                cfg.geospatialMode = Config.GeospatialMode.ENABLED
                configure(cfg)
                setCameraTextureName(cameraTextureId)
            }

            // 4. Playback 모드: resume() 전에 데이터셋 설정
            if (mode == Mode.PLAYBACK && datasetPath != null) {
                localSession.setPlaybackDatasetUri(Uri.fromFile(File(datasetPath)))
                Log.d(TAG, "Playback 데이터셋 설정: $datasetPath")
            }

            // 5. 세션 재개 (카메라 시작 또는 .mp4 재생 시작)
            localSession.resume()
            localSession.setDisplayGeometry(0, 1, 1)

            session = localSession
            earth = localSession.earth
            currentMode = mode
            _isAvailable.value = true
            Log.i(TAG, "ARCore 세션 시작 (mode=$mode)")

            // 6. update 루프 (~30fps)
            while (currentCoroutineContext().isActive) {
                try {
                    val frame = localSession.update()
                    updatePositionFromFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NotYetAvailableException) {
                    // 초기화 중 정상 발생
                } catch (e: Exception) {
                    Log.w(TAG, "session.update() 오류: ${e.javaClass.simpleName}: ${e.message}")
                    delay(200)
                }
                delay(UPDATE_INTERVAL_MS)
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "ARCore 세션 코루틴 취소됨")
        } catch (e: Exception) {
            Log.e(TAG, "ARCore 세션 오류", e)
        } finally {
            // 7. 정리
            try {
                localSession?.pause()
                localSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "세션 종료 오류", e)
            }
            if (cameraTextureId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
                cameraTextureId = -1
            }
            session = null
            earth = null
            teardownEgl()
            Log.d(TAG, "ARCore 세션 정리 완료")
        }
    }

    private fun updatePositionFromFrame(frame: Frame) {
        if (frame.camera.trackingState != TrackingState.TRACKING) {
            Log.v(TAG, "Camera 상태: ${frame.camera.trackingState}")
            return
        }
        val currentEarth = earth ?: return
        if (currentEarth.trackingState != TrackingState.TRACKING) {
            Log.v(TAG, "Earth 상태: ${currentEarth.trackingState}")
            return
        }

        try {
            val pose = currentEarth.cameraGeospatialPose
            @Suppress("DEPRECATION")
            val hAcc = pose.horizontalAccuracy
            if (hAcc > MAX_HORIZONTAL_ACCURACY) {
                Log.d(TAG, "수평 정확도 부족 (${hAcc}m > ${MAX_HORIZONTAL_ACCURACY}m)")
                return
            }

            @Suppress("DEPRECATION")
            val heading = if (pose.headingAccuracy <= MAX_HEADING_ACCURACY) {
                pose.heading.toFloat()
            } else null

            _geospatialPosition.value = FusedPosition(
                coordinate = Coordinate(pose.latitude, pose.longitude),
                accuracy = hAcc.toFloat(),
                altitude = pose.altitude,
                heading = heading,
                source = PositionSource.ARCORE_GEOSPATIAL
            )
            Log.d(TAG, "VPS 위치 업데이트: (${pose.latitude}, ${pose.longitude}), 정확도=${hAcc}m")
        } catch (e: Exception) {
            Log.w(TAG, "위치 추출 오류", e)
        }
    }

    // ── EGL ──────────────────────────────────────────────────────────────────

    /** arCoreThread에서 호출해야 합니다. */
    private fun setupEgl(): Boolean {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val nConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, nConfigs, 0)
            if (nConfigs[0] == 0) {
                Log.e(TAG, "EGL 설정을 찾을 수 없음")
                return false
            }

            val ctx = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(display, surface, surface, ctx)

            eglDisplay = display
            eglCtx = ctx
            eglSurface = surface
            Log.d(TAG, "EGL headless context 설정 완료")
            true
        } catch (e: Exception) {
            Log.e(TAG, "EGL 설정 실패", e)
            false
        }
    }

    /** arCoreThread에서 호출해야 합니다. */
    private fun teardownEgl() {
        try {
            val d = eglDisplay ?: return
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglSurface?.let { EGL14.eglDestroySurface(d, it) }
            eglCtx?.let { EGL14.eglDestroyContext(d, it) }
            EGL14.eglTerminate(d)
            Log.d(TAG, "EGL 해제 완료")
        } catch (e: Exception) {
            Log.w(TAG, "EGL 해제 오류", e)
        } finally {
            eglDisplay = null
            eglCtx = null
            eglSurface = null
        }
    }

    companion object {
        private const val TAG = "GeospatialService"
        private const val MAX_HORIZONTAL_ACCURACY = 25.0
        private const val MAX_HEADING_ACCURACY = 45.0
        private const val UPDATE_INTERVAL_MS = 33L // ~30fps
    }
}
