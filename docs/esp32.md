# ESP32-CAM 연동 가이드

## 개요

ESP32-CAM 모듈이 HTTP MJPEG 스트림 서버로 동작합니다. Android 앱이 이 스트림을 수신하여 YOLO 추론에 사용합니다.

---

## 펌웨어 빌드 및 플래시

### 사전 요구사항

- PlatformIO (VS Code 확장 또는 CLI)
- ESP32-CAM 모듈 + FTDI 어댑터 (플래시용)

### 빌드 및 업로드

```bash
cd smartglass
pio run                    # 빌드만
pio run --target upload    # 플래시
pio device monitor         # 시리얼 모니터 (115200 baud)
```

### WiFi 설정

`smartglass/platformio.ini`의 `build_flags`에서 SSID와 비밀번호를 설정합니다:

```ini
build_flags =
    -DWIFI_SSID=\"YourNetwork\"
    -DWIFI_PASS=\"YourPassword\"
```

---

## 연결 흐름

### 1. 정적 IP (권장)

공유기에서 ESP32의 MAC 주소에 고정 IP를 할당합니다.

시리얼 모니터에서 MAC 주소 확인:
```
[SmartWalker] MAC: AA:BB:CC:DD:EE:FF
[SmartWalker] Ready. http://192.168.1.x/stream
```

공유기 관리 페이지 → DHCP → MAC-IP 바인딩에 위 MAC 주소 등록.

### 2. AP 모드 (WiFi 연결 실패 시 자동 전환)

ESP32가 STA 모드로 WiFi 연결에 실패하면 AP 모드로 전환됩니다:

- SSID: `SmartWalker-XXYYZZ` (XX,YY,ZZ = MAC 마지막 3바이트)
- 비밀번호: `navblind1`
- Android를 위 AP에 연결 후 앱에서 `192.168.4.1` 입력

---

## Android 앱에서 연결

### 방법 1: `.env` 파일로 고정

```env
GLASS_STREAM_URL=http://192.168.1.42/stream
```

이 값이 설정되면 앱 시작 시 자동으로 연결을 시도합니다.

### 방법 2: 앱 내 "기기 추가" 다이얼로그

앱 → 설정 → 기기 연결 → IP 주소 입력

입력한 IP는 Room DB에 저장되어 다음 실행 시 자동 재연결됩니다.

### 연결 실패 시 동작

ESP32 스트림에 연결할 수 없으면 폰 내장 카메라로 자동 fallback합니다. (단, `USE_LOCAL_CAMERA=false`인 경우 객체 인식 기능 비활성화)

---

## ESP32 HTTP 엔드포인트

| 경로 | 메서드 | 설명 |
|------|--------|------|
| `/stream` | GET | MJPEG 스트림 |
| `/status` | GET | 배터리, 신호 강도, 프레임레이트 JSON |
| `/control` | POST | 해상도/품질 조정 |
| `/register` | POST | 서버에 기기 등록 |

---

## 해상도 및 품질

배터리 잔량에 따라 자동으로 품질이 낮아집니다:

| 배터리 | 해상도 | JPEG 품질 |
|--------|--------|-----------|
| 80%+ | SVGA (800×600) | 12 |
| 50–80% | VGA (640×480) | 15 |
| 30–50% | QVGA (320×240) | 20 |
| 30% 미만 | QQVGA (160×120) | 25 |

---

## 문제 해결

**시리얼 출력 없음**: GPIO0을 GND에 연결한 채 리셋. 플래시 후 GPIO0 해제 후 재시작.

**스트림이 끊김**: `/status`에서 `rssi` 확인. -80dBm 이하면 WiFi 신호 약한 것.

**앱에서 연결 안 됨**: 폰과 ESP32가 같은 네트워크에 있는지 확인. 핫스팟 사용 시 ESP32의 WiFi SSID/PASS가 핫스팟과 일치하는지 확인.
