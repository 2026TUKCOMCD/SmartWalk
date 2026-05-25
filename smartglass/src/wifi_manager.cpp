#include "wifi_manager.h"

#ifndef WIFI_SSID
#define WIFI_SSID "NavBlind_Network"
#endif
#ifndef WIFI_PASS
#define WIFI_PASS "navblind2025"
#endif

// AP 모드 기본값 (직접 연결용)
#define AP_SSID_PREFIX "NavBlind-"
#define AP_PASS        "navblind1"

static WifiMode s_currentMode = WIFI_MODE_STA;

WifiMode getCurrentWifiMode() { return s_currentMode; }

// ─── STA 모드 (T090) ─────────────────────────────────────────────────────────
bool connectWifiSTA(const char* ssid, const char* pass) {
    WiFi.mode(WIFI_STA);
    WiFi.begin(ssid ? ssid : WIFI_SSID, pass ? pass : WIFI_PASS);
    Serial.printf("[WiFi] STA connecting to %s", ssid ? ssid : WIFI_SSID);

    for (int i = 0; i < 20 && WiFi.status() != WL_CONNECTED; i++) {
        delay(500);
        Serial.print(".");
    }

    if (WiFi.status() == WL_CONNECTED) {
        s_currentMode = WIFI_MODE_STA;
        Serial.printf("\n[WiFi] STA connected. IP: %s\n",
                      WiFi.localIP().toString().c_str());
        return true;
    }

    Serial.println("\n[WiFi] STA connection FAILED");
    return false;
}

// ─── AP 모드 (T089) ──────────────────────────────────────────────────────────
void startWifiAP() {
    // SSID에 MAC 주소 마지막 3바이트 추가 → 기기마다 고유
    uint8_t mac[6];
    WiFi.macAddress(mac);
    char apSsid[32];
    snprintf(apSsid, sizeof(apSsid), "%s%02X%02X%02X",
             AP_SSID_PREFIX, mac[3], mac[4], mac[5]);

    WiFi.mode(WIFI_AP);
    WiFi.softAP(apSsid, AP_PASS);

    s_currentMode = WIFI_MODE_AP;
    Serial.printf("[WiFi] AP started. SSID=%s IP=%s\n",
                  apSsid, WiFi.softAPIP().toString().c_str());
}

// ─── 기존 connectWifi() — STA 시도 후 실패 시 AP 폴백 ───────────────────────
void connectWifi() {
    if (!connectWifiSTA(nullptr, nullptr)) {
        Serial.println("[WiFi] Fallback to AP mode");
        startWifiAP();
    }
}
