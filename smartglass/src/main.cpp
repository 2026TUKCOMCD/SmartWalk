#include "Arduino.h"
#include "camera.h"
#include "wifi_manager.h"
#include "wifi_stream.h"
#include "battery.h"
#include <WiFi.h>

// ── AI Thinker ESP32-CAM: GPIO 33 = 내장 LED (LOW = ON) ─────────────────────
#define LED_PIN  33
#define LED_ON()  digitalWrite(LED_PIN, LOW)
#define LED_OFF() digitalWrite(LED_PIN, HIGH)

#define BATTERY_CHECK_INTERVAL_MS  60000UL   // 1분
#define IDLE_TIMEOUT_MS           120000UL   // 2분 무활동 → 저전력

static char s_deviceId[20];
static bool s_lowPowerMode = false;

// main.cpp → wifi_stream.cpp 에서 extern 참조
const char* getDeviceId() { return s_deviceId; }

// ── 전력 관리 ─────────────────────────────────────────────────────────────────

static void enterLowPower() {
    if (s_lowPowerMode) return;
    s_lowPowerMode = true;
    WiFi.setTxPower(WIFI_POWER_8_5dBm);
    setCpuFrequencyMhz(80);
    Serial.println("[Power] Low-power mode (80 MHz, TX -8.5 dBm)");
}

static void exitLowPower() {
    if (!s_lowPowerMode) return;
    s_lowPowerMode = false;
    setCpuFrequencyMhz(240);
    WiFi.setTxPower(WIFI_POWER_19_5dBm);
    Serial.println("[Power] Normal mode (240 MHz, TX 19.5 dBm)");
}

// ── LED 유틸 ──────────────────────────────────────────────────────────────────

static void blinkLed(int times, int onMs = 200, int offMs = 200) {
    for (int i = 0; i < times; i++) {
        LED_ON();  delay(onMs);
        LED_OFF(); delay(offMs);
    }
}

// ── setup ─────────────────────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    Serial.println("[NavBlind] Booting...");

    pinMode(LED_PIN, OUTPUT);
    LED_OFF();

    initBattery();

    if (!initCamera()) {
        Serial.println("[Camera] Init FAILED - halting");
        for (;;) blinkLed(1, 100, 100);   // 빠른 점멸 = 카메라 오류
    }

    // T091: 기기 ID 생성 (MAC 마지막 3바이트)
    uint8_t mac[6];
    WiFi.macAddress(mac);
    snprintf(s_deviceId, sizeof(s_deviceId),
             "NAVBLIND-%02X%02X%02X", mac[3], mac[4], mac[5]);
    Serial.printf("[NavBlind] Device ID: %s\n", s_deviceId);

    // T092: WiFi 연결 중 LED ON
    LED_ON();
    connectWifi();
    LED_OFF();

    startStreamServer();

    const char* ipStr = (getCurrentWifiMode() == WIFI_MODE_STA)
        ? WiFi.localIP().toString().c_str()
        : WiFi.softAPIP().toString().c_str();
    Serial.printf("[NavBlind] Ready. http://%s/stream\n", ipStr);

    // T092: 연결 완료 → LED 3회 점멸
    blinkLed(3);
}

// ── loop ──────────────────────────────────────────────────────────────────────

void loop() {
    static unsigned long lastBatCheck = 0;
    unsigned long now = millis();

    // 배터리 주기 체크
    if (now - lastBatCheck >= BATTERY_CHECK_INTERVAL_MS) {
        lastBatCheck = now;
        uint8_t pct = getBatteryPercent();
        checkBatteryWarnings(pct);
        broadcastBatteryLevel(pct);
        Serial.printf("[NavBlind] heap=%u bat=%u%%\n",
                      ESP.getFreeHeap(), pct);
    }

    // T141: 스트림 무활동 → 저전력 모드 전환
    if (!isStreamingEnabled() || (now - lastBatCheck > IDLE_TIMEOUT_MS)) {
        enterLowPower();
    } else {
        exitLowPower();
    }

    delay(5000);
}
