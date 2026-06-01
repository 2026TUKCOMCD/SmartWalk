#pragma once
#include <WiFi.h>

enum WifiMode { WIFI_MODE_STA, WIFI_MODE_AP };

/** STA 모드 연결. 실패 시 false 반환 */
bool connectWifiSTA(const char* ssid = nullptr, const char* pass = nullptr);

/** AP 모드 시작 (직접 연결용, MAC 기반 SSID 자동 생성) */
void startWifiAP();

/** STA 시도 후 실패 시 AP 폴백 (기존 호환 인터페이스) */
void connectWifi();

WifiMode getCurrentWifiMode();
