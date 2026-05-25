#include "battery.h"
#include "wifi_manager.h"
#include <Arduino.h>
#include <HTTPClient.h>
#include <WiFi.h>

#define BATTERY_ADC_PIN  35          // ADC1_CH7 — WiFi 활성 중에도 사용 가능
#define ADC_RESOLUTION   4095.0f
#define ADC_VREF         3.3f
#define DIVIDER_RATIO    2.0f        // 100kΩ + 100kΩ 분압 → 4.2V → 2.1V
#define VBAT_MIN         3.2f        // LiPo 방전 하한 (0%)
#define VBAT_MAX         4.2f        // LiPo 완충 (100%)
#define ADC_SAMPLES      16          // 평균 샘플 수 (노이즈 저감)

static char    s_androidIp[24]      = "";
static uint8_t s_lastWarningLevel   = 100;

// ── 초기화 ────────────────────────────────────────────────────────────────────

void initBattery() {
    analogSetPinAttenuation(BATTERY_ADC_PIN, ADC_11db);  // 0–3.6 V 입력 범위
    for (int i = 0; i < 5; i++) {                        // ADC 워밍업
        analogRead(BATTERY_ADC_PIN);
        delay(2);
    }
    Serial.println("[Battery] ADC init on GPIO 35");
}

// ── Android IP 등록 ───────────────────────────────────────────────────────────

void registerAndroid(const char* ip) {
    strlcpy(s_androidIp, ip, sizeof(s_androidIp));
    Serial.printf("[Battery] Android IP: %s\n", ip);
}

const char* getAndroidIp() { return s_androidIp; }

// ── 배터리 퍼센트 측정 ────────────────────────────────────────────────────────

uint8_t getBatteryPercent() {
    uint32_t sum = 0;
    for (int i = 0; i < ADC_SAMPLES; i++) {
        sum += analogRead(BATTERY_ADC_PIN);
        delay(1);
    }
    float adcVal = sum / (float)ADC_SAMPLES;
    float vAdc   = adcVal / ADC_RESOLUTION * ADC_VREF;
    float vBat   = vAdc * DIVIDER_RATIO;

    float pct = (vBat - VBAT_MIN) / (VBAT_MAX - VBAT_MIN) * 100.0f;
    return (uint8_t)constrain(pct, 0.0f, 100.0f);
}

// ── Android 에 배터리 레벨 POST ───────────────────────────────────────────────

void broadcastBatteryLevel(uint8_t percent) {
    if (s_androidIp[0] == '\0') return;
    if (getCurrentWifiMode() == WIFI_MODE_STA &&
        WiFi.status() != WL_CONNECTED) return;

    HTTPClient http;
    char url[64];
    snprintf(url, sizeof(url), "http://%s:8765/battery", s_androidIp);
    http.begin(url);
    http.addHeader("Content-Type", "application/json");

    char body[32];
    snprintf(body, sizeof(body), "{\"level\":%u}", percent);
    int code = http.POST(body);
    http.end();

    if (code > 0) {
        Serial.printf("[Battery] → %s %u%% (HTTP %d)\n",
                      s_androidIp, percent, code);
    } else {
        Serial.printf("[Battery] POST failed: %s\n",
                      HTTPClient::errorToString(code).c_str());
    }
}

// ── 경보 임계값 체크 (50 / 20 / 10 %) ─────────────────────────────────────────

void checkBatteryWarnings(uint8_t percent) {
    const uint8_t thresholds[] = {10, 20, 50};
    for (uint8_t t : thresholds) {
        if (percent <= t && s_lastWarningLevel > t) {
            Serial.printf("[Battery] WARNING %u%% ≤ %u%% threshold\n",
                          percent, t);
            s_lastWarningLevel = t;
            broadcastBatteryLevel(percent);   // 임계 도달 시 즉시 전송
            return;
        }
    }
    if (percent > 55 && s_lastWarningLevel < 100) {
        s_lastWarningLevel = 100;             // 충전 후 경보 리셋
    }
}
