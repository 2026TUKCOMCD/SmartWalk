# SmartWalker - 시각장애인용 스마트글래스 네비게이션 시스템

ESP32-CAM 스마트글래스 + Android 앱 + Spring Boot 백엔드로 구성된 실시간 길안내 시스템

## 환경 설정

### 백엔드 `.env`
```bash
cp backend/.env.example backend/.env
```
```env
# Kakao Developers → 내 애플리케이션 → 앱 키 → REST API 키
KAKAO_API_KEY=your_kakao_rest_api_key_here
# false(기본값): 실제 Firebase 토큰 검증 — backend/smartwalker-firebase-adminsdk-key.json 필요(git 제외, Firebase 콘솔에서 발급)
# true: X-User-Id 헤더로 인증 대체(로컬 개발용, Firebase 키 없이 실행 가능)
FIREBASE_DISABLED=false
```
`.env`는 [spring-dotenv](https://github.com/paulschwarz/spring-dotenv)가 `mvnw spring-boot:run` 실행 시 자동으로 읽습니다(별도 export 불필요).

### Android `.env`
```bash
cp android/.env.example android/.env
```
```env
SERVER_HOST=100.122.72.41     # 백엔드 서버 IP (Tailscale) 또는 10.0.2.2 (에뮬레이터)
USE_LOCAL_CAMERA=false        # false = ESP32-CAM, true = 스마트폰 카메라
# GLASS_STREAM_URL=http://192.168.43.xxx/stream  # ESP32 유동 IP일 때 직접 지정

# Kakao Maps SDK Native App Key (지도 표시용, 백엔드 KAKAO_API_KEY와는 별개 키)
# Kakao Developers 콘솔 → 해당 앱 → Android 플랫폼에 패키지명(com.smartwalker) + 키해시 등록 필요
KAKAO_NATIVE_APP_KEY=your_kakao_native_app_key_here
```
> 키해시 확인: `keytool -exportcert -alias androiddebugkey -keystore %USERPROFILE%\.android\debug.keystore -storepass android -keypass android | openssl sha1 -binary | openssl base64`
> (릴리즈 서명 키스토어를 별도로 설정하지 않는 한 디버그/릴리즈 빌드 모두 이 debug.keystore를 쓰므로 키해시는 하나만 등록하면 됩니다.)

---

## 실행

### 1. 인프라 — PostgreSQL + Redis + OSRM (Kakao 지오코딩/검색은 인프라 불필요, 백엔드가 REST로 직접 호출)
```bash
npm run docker:dev     # postgres + redis + OSRM 데모 프록시(공개 서버 경유, 데이터 준비 불필요)
```
> 실제 한국 전역 OSRM 데이터로 라우팅하려면 아래 [전체 OSRM 데이터 사용] 참고.

### 2. 백엔드 서버
```bash
npm run backend:dev    # Spring Boot 실행 → http://localhost:8080
```

### 3. Android 앱 설치 (adb)
```bash
adb devices             # 기기 연결 확인 (USB 디버깅 또는 adb connect <ip>:5555)

npm run android:dev     # gradlew installDebug — 빌드 후 adb install까지 자동 실행
npm run android:prod    # gradlew installRelease — 프로덕션 빌드 설치
```
수동으로 APK만 빌드해 설치하려면:
```bash
cd android && gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 전체 OSRM 데이터 사용 (한국 전역 정밀 라우팅)
```bash
# 최초 1회 — OSRM 한국 데이터 다운로드 + 전처리 (20~40분)
npm run docker:setup

# 이후 매번 — 실제 OSRM + postgres + redis 기동
npm run docker:full
```

### 기타
```bash
npm run docker:logs    # 서비스 로그 실시간 확인
npm run docker:down    # 모든 Docker 서비스 종료
npm run backend:build  # JAR 빌드 (배포용)
```

---

## 프로젝트 구조

```
smartwalker/
├── android/                  # Android 앱 (Kotlin + Jetpack Compose)
│   ├── .env                  # 로컬 환경 설정 (git 제외)
│   ├── .env.example          # 환경 설정 예시
│   └── app/src/main/java/com/smartwalker/
│       ├── data/             # Repository, API, Room DB
│       ├── domain/           # UseCase, Model
│       ├── presentation/     # UI (Compose)
│       └── service/          # 위치·음성·스트리밍·YOLO
├── backend/                  # Spring Boot 서버 (Java 21)
│   ├── smartwalker-firebase-adminsdk-key.json  # Firebase 키 (git 제외, FIREBASE_DISABLED=false 일 때 필요)
│   └── src/main/java/com/navblind/server/
│       ├── controller/       # REST API
│       ├── service/          # 비즈니스 로직
│       ├── entity/           # JPA 엔티티
│       └── integration/      # OSRM, Kakao Local 클라이언트
├── smartglass/               # ESP32-CAM 펌웨어 (C++)
│   └── src/
├── docker/                   # 인프라
│   └── docker-compose.yml
└── package.json              # 통합 실행 스크립트
```

---

## 기술 스택

| 구성 요소 | 기술 |
|---|---|
| Android | Kotlin, Jetpack Compose, TFLite (YOLOv8), Kakao Maps SDK, Hilt |
| Backend | Java 21, Spring Boot 3.5.10, PostgreSQL, Redis |
| 라우팅 | OSRM (self-hosted) |
| 지오코딩/장소검색/지도 | Kakao Local API (백엔드), Kakao Maps SDK (Android) |
| 인증 | Firebase Phone Auth |
| 스마트글래스 | ESP32-CAM, MJPEG over HTTP |

## API 주요 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/v1/navigation/route` | 경로 계산 |
| POST | `/v1/navigation/reroute` | 경로 재탐색 |
| GET | `/v1/destinations/search?query=경복궁` | 장소 검색 |
| POST | `/v1/auth/verify` | Firebase 토큰 검증 |
