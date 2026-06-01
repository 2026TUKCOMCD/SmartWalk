#include "wifi_stream.h"
#include "battery.h"
#include "esp_camera.h"
#include "esp_http_server.h"
#include <Arduino.h>
#include <cstring>

#define PART_BOUNDARY "123456789000000000000987654321"
static const char* STREAM_CONTENT_TYPE =
    "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char* STREAM_PART =
    "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

// ── 전역 상태 ─────────────────────────────────────────────────────────────────

static volatile bool     s_streamEnabled  = true;
static volatile uint32_t s_lastActivityMs = 0;

extern const char* getDeviceId();   // main.cpp 에서 제공

void markStreamActivity() { s_lastActivityMs = millis(); }
bool isStreamingEnabled()  { return s_streamEnabled; }

// ── MJPEG 스트리밍 핸들러 ─────────────────────────────────────────────────────

static esp_err_t streamHandler(httpd_req_t* req) {
    if (!s_streamEnabled) {
        httpd_resp_send_err(req, HTTPD_503_SERVICE_UNAVAILABLE, "Stream paused");
        return ESP_OK;
    }

    camera_fb_t* fb = nullptr;
    char         part_buf[64];

    esp_err_t res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
    if (res != ESP_OK) return res;
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

    while (true) {
        if (!s_streamEnabled) break;

        fb = esp_camera_fb_get();
        if (!fb) {
            Serial.println("[Stream] Capture failed");
            res = ESP_FAIL;
            break;
        }

        markStreamActivity();

        size_t hlen = snprintf(part_buf, sizeof(part_buf),
                               STREAM_PART, fb->len);
        res = httpd_resp_send_chunk(req, STREAM_BOUNDARY,
                                    strlen(STREAM_BOUNDARY));
        if (res == ESP_OK)
            res = httpd_resp_send_chunk(req, part_buf, hlen);
        if (res == ESP_OK)
            res = httpd_resp_send_chunk(req, (const char*)fb->buf, fb->len);

        esp_camera_fb_return(fb);
        fb = nullptr;
        if (res != ESP_OK) break;
    }
    return res;
}

// ── T060: HTTP 제어 채널 (start / stop) ──────────────────────────────────────

static esp_err_t controlHandler(httpd_req_t* req) {
    char body[64] = {};
    int  recv     = httpd_req_recv(req, body, sizeof(body) - 1);
    if (recv <= 0) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Empty body");
        return ESP_OK;
    }

    if (strstr(body, "\"command\":\"stop\"")) {
        s_streamEnabled = false;
        Serial.println("[Control] Stream STOPPED");
    } else if (strstr(body, "\"command\":\"start\"")) {
        s_streamEnabled = true;
        markStreamActivity();
        Serial.println("[Control] Stream STARTED");
    } else {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Unknown command");
        return ESP_OK;
    }

    httpd_resp_set_type(req, "application/json");
    const char* resp = s_streamEnabled
        ? "{\"streaming\":true}"
        : "{\"streaming\":false}";
    httpd_resp_sendstr(req, resp);
    return ESP_OK;
}

// ── /status: 기기 정보 조회 ───────────────────────────────────────────────────

static esp_err_t statusHandler(httpd_req_t* req) {
    extern uint8_t getBatteryPercent();
    uint8_t bat = getBatteryPercent();

    char buf[256];
    snprintf(buf, sizeof(buf),
             "{\"deviceId\":\"%s\",\"streaming\":%s,\"battery\":%u,\"heap\":%u}",
             getDeviceId(),
             s_streamEnabled ? "true" : "false",
             bat,
             (unsigned)ESP.getFreeHeap());

    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_sendstr(req, buf);
    return ESP_OK;
}

// ── /register: Android IP 등록 ───────────────────────────────────────────────

static esp_err_t registerHandler(httpd_req_t* req) {
    char body[64] = {};
    int  recv     = httpd_req_recv(req, body, sizeof(body) - 1);
    if (recv <= 0) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Empty body");
        return ESP_OK;
    }

    // 간단 파싱: {"ip":"192.168.x.x"}
    char* ipStart = strstr(body, "\"ip\":\"");
    if (!ipStart) {
        httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Missing ip field");
        return ESP_OK;
    }
    ipStart += 6;
    char* ipEnd = strchr(ipStart, '"');
    if (ipEnd) *ipEnd = '\0';

    registerAndroid(ipStart);

    httpd_resp_set_type(req, "application/json");
    httpd_resp_sendstr(req, "{\"registered\":true}");
    return ESP_OK;
}

// ── 서버 시작 ─────────────────────────────────────────────────────────────────

void startStreamServer() {
    httpd_config_t config  = HTTPD_DEFAULT_CONFIG();
    config.server_port     = 80;
    config.max_uri_handlers = 8;

    httpd_handle_t server = nullptr;
    if (httpd_start(&server, &config) != ESP_OK) {
        Serial.println("[Stream] Server start FAILED");
        return;
    }

    const httpd_uri_t uris[] = {
        { "/stream",   HTTP_GET,  streamHandler,   nullptr },
        { "/control",  HTTP_POST, controlHandler,  nullptr },
        { "/status",   HTTP_GET,  statusHandler,   nullptr },
        { "/register", HTTP_POST, registerHandler, nullptr },
    };
    for (const auto& u : uris)
        httpd_register_uri_handler(server, &u);

    Serial.println("[Stream] HTTP server started on port 80");
    Serial.println("[Stream] Endpoints: /stream /control /status /register");
}
