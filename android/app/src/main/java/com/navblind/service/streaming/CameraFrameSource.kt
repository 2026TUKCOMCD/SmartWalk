package com.navblind.service.streaming

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 카메라 프레임 소스 인터페이스.
 *
 * 구현체:
 *  - [LocalCameraSource]: CameraX를 이용한 스마트폰 내장 카메라
 *  - [MjpegCameraSource]: ESP32-CAM HTTP MJPEG 스트림
 *
 * YOLO 파이프라인과 DataCollectionService가 이 인터페이스를 통해 프레임을 받아
 * 소스 전환 시 상위 레이어의 코드 변경 없이 교체할 수 있습니다.
 */
interface CameraFrameSource {

    /** 연속 발행되는 카메라 프레임 (수집자가 없어도 손실 허용) */
    val frames: Flow<Bitmap>

    /** 현재 프레임 수집이 실행 중인지 여부 */
    val isRunning: StateFlow<Boolean>

    /** 프레임 수집 시작 */
    fun start()

    /** 프레임 수집 중지 */
    fun stop()
}
