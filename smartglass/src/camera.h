#pragma once
#include <stdbool.h>

/**
 * ESP32-CAM (AI Thinker) 카메라를 초기화한다.
 * @return true  성공
 * @return false 실패 (시리얼 로그 참조)
 */
bool initCamera();
