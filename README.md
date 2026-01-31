# NavBlind - Smart Glass Navigation System for Visually Impaired Users

시각장애인을 위한 스마트글래스 네비게이션 시스템

## Quick Start

### 1. Backend 실행

```bash
# Docker 서비스 시작 (PostgreSQL, Redis)
cd docker
docker-compose up -d postgres redis

# Backend 실행
cd ../backend
./mvnw spring-boot:run
```

Backend가 `http://localhost:8080`에서 실행됩니다.

### 2. Android 앱 빌드

```bash
cd android
./gradlew assembleDebug
```

또는 Android Studio에서 프로젝트를 열고 실행합니다.

## 프로젝트 구조

```
capstoneDesign/
├── android/                 # Android 앱 (Kotlin)
│   └── app/src/main/java/com/navblind/
│       ├── data/           # 데이터 레이어
│       ├── domain/         # 도메인 레이어
│       ├── presentation/   # UI 레이어
│       └── service/        # 서비스 (위치, 음성)
├── backend/                 # Spring Boot 서버 (Java 21)
│   └── src/main/java/com/navblind/server/
│       ├── controller/     # REST API
│       ├── service/        # 비즈니스 로직
│       ├── entity/         # JPA 엔티티
│       └── integration/    # 외부 API 클라이언트
├── docker/                  # Docker 설정
│   └── docker-compose.yml
└── specs/                   # 프로젝트 명세
```

## API 엔드포인트

### 경로 탐색
- `POST /v1/navigation/route` - 경로 계산
- `POST /v1/navigation/reroute` - 경로 재탐색

### 목적지 검색
- `GET /v1/destinations/search?query=경복궁` - 장소 검색

## 주요 기능

1. **음성/텍스트 목적지 검색** - OSM Nominatim API 연동
2. **경로 탐색** - OSRM 라우팅 엔진 활용
3. **실시간 위치 추적** - GPS + ARCore Geospatial API 융합
4. **경로 이탈 감지** - 15m 이탈 시 자동 재탐색
5. **음성 안내** - TTS를 통한 한국어 길안내

## 기술 스택

- **Backend**: Java 21, Spring Boot 3.5.10, PostgreSQL, Redis
- **Android**: Kotlin, Jetpack Compose, Hilt, ARCore
- **Infrastructure**: Docker, OSRM, Nginx
