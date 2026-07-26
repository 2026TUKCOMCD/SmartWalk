# 개발 환경 설정

처음 프로젝트를 클론한 팀원을 위한 셋업 가이드입니다.

## 사전 요구사항

| 도구 | 버전 | 용도 |
|---|---|---|
| Android Studio | Hedgehog 이상 | Android 앱 개발 |
| JDK | 21 | 백엔드 빌드 |
| Docker Desktop | 24+ | PostgreSQL, Redis |
| PlatformIO | 최신 | ESP32 펌웨어 |
| Node.js | 18+ | npm 스크립트 실행 |

---

## 1. 저장소 클론

```bash
git clone <repo-url>
cd capstoneDesign
```

---

## 2. Android 환경 설정

```bash
cp android/.env.example android/.env
```

`android/.env` 수정:

```env
SERVER_HOST=10.0.2.2          # 에뮬레이터
# SERVER_HOST=100.122.72.41   # 실기기 (Tailscale IP)
ARCORE_API_KEY=발급받은_키
USE_LOCAL_CAMERA=false
```

`android/local.properties` — `sdk.dir` 경로 확인:
```
sdk.dir=C\:\\android_dev\\Sdk
```

---

## 3. 백엔드 환경 설정

```bash
cp backend/.env.example backend/.env
```

`backend/.env` 수정 — Kakao Developers → 내 애플리케이션 → 앱 키 → REST API 키:

```env
KAKAO_API_KEY=발급받은_REST_API_키
FIREBASE_DISABLED=false
```

Firebase Admin SDK 키가 `backend/smartwalker-firebase-adminsdk-key.json` 에 있는지 확인합니다.
없으면 Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → JSON 키 다운로드 후 해당 위치에 저장.
(로컬 개발 중 Firebase 없이 진행하려면 `FIREBASE_DISABLED=true` — X-User-Id 헤더로 인증 대체)

---

## 4. Docker 서비스 시작

### 기본 (백엔드 개발, 경로/지오코딩 불필요)

```bash
npm run docker:up
```

PostgreSQL(`5432`), Redis(`6379`) 가 뜨는지 확인:
```bash
npm run docker:logs
```

### 전체 한국 OSRM 데이터 포함 (정밀 경로 탐색 기능 개발 시)

> 장소 검색/지오코딩은 Kakao Local API(REST 호출)라 별도 인프라가 필요 없습니다 — 위 2단계에서 `KAKAO_API_KEY`만 설정하면 됩니다.

**최초 1회** — 한국 OSM 데이터 다운로드 + OSRM 전처리 (20~40분 소요):
```bash
npm run docker:setup
```

**이후 매번** — 실제 OSRM + postgres + redis 기동:
```bash
npm run docker:full
```

> OSRM 데이터 준비 없이 경로 API 형태만 테스트하려면 `npm run docker:dev` (공개 OSRM 데모 서버 프록시 사용)

| 커맨드 | 포함 서비스 | 용도 |
|--------|------------|------|
| `docker:up` | postgres, redis | 기본 백엔드 개발 |
| `docker:dev` | + osrm-demo(프록시) | 경로 API 형태 확인 |
| `docker:full` | + 실제 osrm, nginx | 풀스택 개발 |

---

## 5. 백엔드 실행

```bash
npm run backend:dev
# → http://localhost:8080 에서 실행
```

---

## 6. Android 앱 설치 (USB 디버깅)

휴대폰을 USB로 연결 후:
```bash
npm run android:dev
```

---

## 전체 한 번에 시작

```bash
npm run dev
# Docker(postgres, redis) 백그라운드 + Spring Boot 포그라운드
```
