package com.navblind.service.streaming

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

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

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
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
    }
}
