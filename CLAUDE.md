# NavBlind Development Guidelines

Auto-generated from feature plans. Last updated: 2026-01-30

## Project Overview

시각장애인용 스마트글래스 네비게이션 시스템
- ESP32-CAM 기반 스마트글래스 (영상 스트리밍)
- Android 모바일 앱 (YOLO 객체인식, 음성 안내)
- Spring Boot 백엔드 (경로 탐색, 사용자 관리)

## Active Technologies

### Mobile (Android)
- **Language**: Kotlin
- **SDK**: Android API 34 (min 26)
- **Dependencies**: TensorFlow Lite, ARCore, Retrofit, Room, Hilt
- **Feature**: 001-smartglass-nav-system

### Backend (Server)
- **Language**: Java 21
- **Framework**: Spring Boot 3.5.10
- **Database**: PostgreSQL 16
- **Cache**: Redis 7
- **Routing**: OSRM (self-hosted)

### Smart Glasses (Firmware)
- **Platform**: ESP32-CAM
- **Language**: C++ (Arduino/ESP-IDF)
- **Protocol**: MJPEG over HTTP

### Infrastructure
- Docker Compose
- Nginx (reverse proxy)
- AWS (production)

## Project Structure

```text
android/                    # Android app (Kotlin)
├── app/src/main/java/com/navblind/
│   ├── data/              # Repository, API clients
│   ├── domain/            # Use cases, models
│   ├── presentation/      # UI (Compose/XML)
│   ├── service/           # Background services
│   │   ├── detection/     # YOLO object detection
│   │   ├── location/      # GPS + ARCore fusion
│   │   ├── streaming/     # ESP32-CAM receiver
│   │   └── voice/         # TTS, Speech recognition
│   └── di/                # Hilt modules

backend/                    # Spring Boot server (Java)
├── src/main/java/com/navblind/server/
│   ├── config/            # Spring configuration
│   ├── controller/        # REST controllers
│   ├── service/           # Business logic
│   ├── repository/        # JPA repositories
│   ├── entity/            # JPA entities
│   ├── dto/               # DTOs
│   └── integration/       # OSRM client

smartglass/                 # ESP32-CAM firmware (C++)
├── src/
│   ├── main.cpp
│   ├── camera.cpp
│   └── wifi_stream.cpp

docker/                     # Infrastructure
├── docker-compose.yml
├── nginx/
└── osrm/

specs/001-smartglass-nav-system/  # Documentation
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/api.yaml
└── quickstart.md
```

## Commands

### Android
```bash
cd android
./gradlew assembleDebug          # Build debug APK
./gradlew testDebugUnitTest      # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests
./gradlew lintDebug              # Lint check
```

### Backend
```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'  # Run locally
./gradlew test                   # Run tests
./gradlew build                  # Build JAR
```

### ESP32-CAM
```bash
cd smartglass
pio run                          # Build
pio run --target upload          # Flash
pio device monitor               # Serial monitor
```

### Docker
```bash
cd docker
docker-compose up -d             # Start all services
docker-compose logs -f backend   # View logs
docker-compose down              # Stop all
```

## Code Style

### Kotlin (Android)
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use Coroutines for async operations
- Prefer Flow over LiveData for reactive streams
- Use Hilt for dependency injection

### Java (Backend)
- Follow Google Java Style Guide
- Use records for DTOs
- Prefer constructor injection
- Use @Transactional appropriately

### C++ (ESP32)
- Follow Arduino style for embedded code
- Keep memory usage minimal
- Avoid dynamic allocation where possible

## API Contracts

OpenAPI specification: `specs/001-smartglass-nav-system/contracts/api.yaml`

Key endpoints:
- `POST /v1/auth/verify` - Firebase token verification
- `POST /v1/navigation/route` - Calculate pedestrian route
- `POST /v1/navigation/reroute` - Recalculate after deviation
- `GET /v1/destinations` - User's saved destinations
- `GET /v1/destinations/search` - Search OSM POIs

## Database Schema

See `specs/001-smartglass-nav-system/data-model.md` for full schema.

Key entities:
- User (phone auth)
- Destination (saved places)
- Preference (accessibility settings)
- NavigationSession (active/history)
- SmartGlasses (paired devices)

## Testing

### Unit Tests
- Android: JUnit 5, MockK
- Backend: JUnit 5, Mockito

### Integration Tests
- Backend: TestContainers (PostgreSQL, Redis)
- Android: Espresso (UI), Robolectric (headless)

### E2E Tests
- Manual testing with real hardware
- Video feed → Object detection → Voice output

## Recent Changes

- 001-smartglass-nav-system: Initial feature specification and planning complete

<!-- MANUAL ADDITIONS START -->
<!-- Add project-specific notes below this line -->

<!-- MANUAL ADDITIONS END -->
