package com.smartwalker.service.streaming

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CameraX를 이용해 스마트폰 후면 카메라에서 프레임을 수집합니다.
 *
 * ESP32-CAM 펌웨어가 준비되지 않은 경우 또는 실내 테스트 시 사용합니다.
 * [ProcessLifecycleOwner]에 바인딩되어 앱 전체 생명주기 동안 유지됩니다.
 *
 * 수집된 Bitmap은 [frames] SharedFlow로 발행되며,
 * YoloObjectDetector 또는 DataCollectionService 등 여러 소비자가 동시에 구독할 수 있습니다.
 */
@Singleton
class LocalCameraSource @Inject constructor(
    @ApplicationContext private val context: Context
) : CameraFrameSource {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    // 프레임 변환 스로틀: 소비자(YOLO)가 500ms마다만 샘플링하므로
    // 카메라 센서 속도(~30fps)로 들어오는 모든 프레임을 Bitmap으로 변환하는 것은 낭비다.
    // ~5fps로 제한해 toBitmap() 호출 빈도를 줄이고 발열을 낮춘다.
    @Volatile private var lastFrameTimeMs = 0L

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override val sourceType: CameraSourceType = CameraSourceType.LOCAL_PHONE

    // DROP_OLDEST: YOLO 처리 속도보다 카메라가 빠를 때 오래된 프레임 자동 폐기
    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val frames: Flow<Bitmap> = _frames.asSharedFlow()

    /**
     * 카메라를 시작합니다.
     * ProcessCameraProvider 초기화는 메인 스레드에서 비동기로 실행됩니다.
     */
    override fun start() {
        if (_isRunning.value) return

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                // 저해상도 요청 → ISP/변환 부하 감소 (YOLO 입력은 어차피 640으로 리사이즈됨)
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    // 스로틀: 직전 변환 후 FRAME_INTERVAL_MS 이내면 변환 없이 폐기
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTimeMs < FRAME_INTERVAL_MS) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    lastFrameTimeMs = now
                    try {
                        // CameraX 1.3+ 내장 변환 (YUV_420_888 → Bitmap)
                        val bitmap = imageProxy.toBitmap()
                        _frames.tryEmit(bitmap)
                    } catch (e: Exception) {
                        Log.w(TAG, "프레임 변환 실패", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis
                )
                _isRunning.value = true
                Log.d(TAG, "내장 카메라 시작")
            } catch (e: Exception) {
                Log.e(TAG, "카메라 초기화 실패", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 카메라를 중지하고 리소스를 해제합니다.
     */
    override fun stop() {
        cameraProvider?.unbindAll()
        _isRunning.value = false
        Log.d(TAG, "내장 카메라 중지")
    }

    companion object {
        private const val TAG = "LocalCameraSource"
        // 분석용 목표 해상도 (640×480이면 YOLO 640 입력에 충분)
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
        // 프레임 변환 최소 간격 (~5fps). 소비자 샘플링(500ms)보다 충분히 잦음.
        private const val FRAME_INTERVAL_MS = 180L
    }
}
