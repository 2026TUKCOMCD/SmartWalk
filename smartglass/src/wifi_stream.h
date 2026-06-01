#pragma once

void startStreamServer();
void markStreamActivity();      // 스트림 활성 시 호출 → 저전력 모드 억제
bool isStreamingEnabled();
