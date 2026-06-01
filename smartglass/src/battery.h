#pragma once
#include <stdint.h>

void initBattery();
uint8_t getBatteryPercent();
void broadcastBatteryLevel(uint8_t percent);   // HTTP POST to connected Android
void checkBatteryWarnings(uint8_t percent);    // 50/20/10% 경보 로그

void registerAndroid(const char* ip);          // Android IP 등록 (배터리 브로드캐스트 대상)
const char* getAndroidIp();
