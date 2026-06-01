# Android 앱 개발 가이드

## 환경 설정

### 사전 요구사항

- Android Studio Hedgehog 이상
- JDK 21 (Gradle wrapper가 자동으로 사용)
- `android/.env` 파일 (setup.md 참고)

### .env 설정

```bash
cp android/.env.example android/.env
```

`android/.env` 수정:

```env
SERVER_HOST=10.0.2.2          # 에뮬레이터 기본값
# SERVER_HOST=192.168.x.x    # 실기기 — 같은 WiFi 또는 Tailscale IP
ARCORE_API_KEY=발급받은_키
USE_LOCAL_CAMERA=false        # true면 폰 카메라 직접 사용 (배터리 소모 큼)
# GLASS_STREAM_URL=http://192.168.43.xxx/stream  # ESP32 스트림 URL
```

`android/local.properties`:
```
sdk.dir=C\:\\android_dev\\Sdk
```

---

## 빌드 및 설치

| 명령어 | 설명 |
|--------|------|
| `npm run android:dev` | Debug APK 빌드 후 USB 연결 기기에 설치 |
| `npm run android:prod` | Release APK 빌드 후 USB 연결 기기에 설치 |
| `npm run android:build` | Debug APK만 빌드 (설치 안 함) |
| `npm run android:build:prod` | Release APK만 빌드 (설치 안 함) |

USB 디버깅 절차:
1. 휴대폰 설정 → 개발자 옵션 → USB 디버깅 활성화
2. USB로 PC에 연결
3. `adb devices` 로 인식 확인
4. `npm run android:dev` 실행

---

## 프로젝트 구조

```
android/app/src/main/java/com/smartwalker/
├── data/
│   ├── api/           # Retrofit 인터페이스
│   └── repository/    # Repository 구현체
├── domain/
│   ├── model/         # 도메인 모델
│   └── usecase/       # Use Case
├── presentation/
│   ├── screen/        # Compose 화면
│   └── viewmodel/     # ViewModel
├── service/
│   ├── detection/     # YOLO 객체 인식 (TFLite)
│   ├── location/      # GPS + ARCore 위치 융합
│   ├── streaming/     # ESP32-CAM MJPEG 수신
│   └── voice/         # TTS / 음성 인식
└── di/                # Hilt 모듈
```

---

## 주요 기능

### 객체 인식 (YOLO)

- 모델: `assets/yolov8n.tflite`
- 클래스: 신호등(적/녹), 횡단보도, 장애물, 계단, 점자블록
- 실행: `ObjectDetectionService` — 30fps 추론, 위험 객체 TTS 알림

커스텀 모델로 교체 시 `assets/yolov8n.tflite` 파일을 교체하고 `DetectionLabels.kt`의 클래스 목록을 동기화합니다.

### 위치 추적

- GPS: 실외 기본 위치
- ARCore: 실내/음영 지역 보정 (VPS 미지원 지역에서는 GPS fallback)
- `LocationFusionService`가 두 소스를 칼만 필터로 융합

### ESP32-CAM 스트리밍

- `GlassStreamingService`가 `GLASS_STREAM_URL`로 MJPEG 스트림 수신
- 기기 IP는 앱 내 "기기 추가" 다이얼로그 또는 `.env`의 `GLASS_STREAM_URL`로 설정
- 연결 실패 시 폰 내장 카메라 자동 fallback

### 음성 안내

- TTS: Android `TextToSpeech` (한국어)
- STT: `SpeechRecognizer` (목적지 입력)
- 경로 안내: 교차로 50m 전 방향 알림, 재탐색 시 즉시 알림

---

## TalkBack 접근성

모든 화면은 TalkBack 스크린리더로 조작 가능합니다.

테스트 방법:
1. 설정 → 접근성 → TalkBack 활성화
2. 탐색 화면에서 스와이프로 버튼 이동, 더블탭으로 실행
3. 음성 출력 내용이 컨텍스트에 맞는지 확인

---

## 단위 테스트

```bash
cd android
./gradlew testDebugUnitTest
```

계측 테스트 (기기 필요):
```bash
./gradlew connectedAndroidTest
```

---

## 빌드 타입

| 타입 | SERVER_HOST | 카메라 |
|------|-------------|--------|
| debug | `.env`에서 읽음 | `.env` 설정 |
| release | `https://api.navblind.com/v1` (하드코딩) | ESP32 기본 |

Release 빌드 시 서명 키가 없으면 `~/.android/debug.keystore`로 자동 fallback합니다.
