# Implementation Plan: Smart Glass Navigation System for Visually Impaired Users

**Branch**: `001-smartglass-nav-system` | **Date**: 2026-01-30 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-smartglass-nav-system/spec.md`

## Summary

A navigation assistance system for visually impaired users comprising:
1. **ESP32-CAM Smart Glasses** - Captures and streams real-time video via Wi-Fi
2. **Android Mobile App (Kotlin)** - Processes video with YOLO for obstacle detection, provides voice guidance, uses ARCore Geospatial + IMU + visual odometry for GPS correction
3. **Backend Server (Spring Boot)** - Provides OSM data, OSRM routing, user management via REST API
4. **Infrastructure** - Docker containers, Nginx reverse proxy, Redis caching, PostgreSQL database

## Technical Context

### Mobile Application
**Language/Version**: Kotlin (Android SDK 34, min SDK 26)
**Primary Dependencies**:
- Google ARCore Geospatial API (GPS correction)
- TensorFlow Lite + YOLO model (object detection)
- Android Speech Recognition & TTS (voice I/O)
- OkHttp/Retrofit (API communication)
- OSMdroid (offline map rendering)
- IMU sensor libraries (accelerometer, gyroscope fusion)

**Target Platform**: Android 8.0+ (API 26+)
**Performance Goals**:
- Object detection: <2 seconds latency (SC-002)
- GPS accuracy: within 5 meters (SC-003)
- Voice response: <5 seconds for navigation start (SC-001)
- Frame processing: 10-15 FPS for real-time detection

**Constraints**:
- On-device ML inference (offline-capable)
- Battery optimization for continuous use
- Wi-Fi streaming from ESP32-CAM

### Backend Server
**Language/Version**: Java 21
**Framework**: Spring Boot 3.5.10
**Primary Dependencies**:
- Spring Web (REST API)
- Spring Data JPA (database access)
- Spring Security (authentication)
- OSRM (routing engine integration)
- Redis (caching, session management)

**Storage**:
- PostgreSQL (user data, preferences, destinations)
- Redis (session cache, route cache)
- OSM data files (map tiles)

**Testing**: JUnit 5, Mockito, TestContainers
**Target Platform**: Docker containers → AWS deployment

**Infrastructure**:
- Nginx (reverse proxy, SSL termination)
- Docker Compose (local development)
- OSRM container (routing service)
- Redis container (caching)
- PostgreSQL container (database)

### Smart Glasses (ESP32-CAM)
**Platform**: ESP32-CAM module
**Language**: C++ (Arduino/ESP-IDF)
**Protocol**: MJPEG over HTTP/WebSocket (Wi-Fi streaming)
**Constraints**:
- Limited processing power (streaming only)
- Battery-powered operation
- Compact form factor

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| Modular Architecture | PASS | Clear separation: ESP32-CAM, Android App, Backend Server |
| Testability | PASS | Each component independently testable |
| Simplicity | PASS | Using established frameworks (Spring Boot, TensorFlow Lite) |
| Documentation | PASS | Spec complete with clarifications |

**Gate Status**: PASSED - Proceeding to Phase 0

## Project Structure

### Documentation (this feature)

```text
specs/001-smartglass-nav-system/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (API specs)
│   └── api.yaml         # OpenAPI specification
└── tasks.md             # Phase 2 output
```

### Source Code (repository root)

```text
# Mobile Application (Android/Kotlin)
android/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/navblind/
│       │   │   ├── data/              # Data layer
│       │   │   │   ├── local/         # Room database, SharedPrefs
│       │   │   │   ├── remote/        # API clients (Retrofit)
│       │   │   │   └── repository/    # Repository implementations
│       │   │   ├── domain/            # Business logic
│       │   │   │   ├── model/         # Domain models
│       │   │   │   ├── usecase/       # Use cases
│       │   │   │   └── repository/    # Repository interfaces
│       │   │   ├── presentation/      # UI layer
│       │   │   │   ├── navigation/    # Navigation screens
│       │   │   │   ├── settings/      # Settings screens
│       │   │   │   └── common/        # Shared UI components
│       │   │   ├── service/           # Background services
│       │   │   │   ├── detection/     # YOLO object detection
│       │   │   │   ├── location/      # GPS + ARCore + IMU fusion
│       │   │   │   ├── streaming/     # ESP32-CAM video receiver
│       │   │   │   └── voice/         # TTS and speech recognition
│       │   │   └── di/                # Dependency injection (Hilt)
│       │   └── res/                   # Resources
│       └── test/                      # Unit tests
└── gradle/

# Backend Server (Java/Spring Boot)
backend/
├── src/
│   ├── main/
│   │   ├── java/com/navblind/server/
│   │   │   ├── config/           # Spring configuration
│   │   │   ├── controller/       # REST controllers
│   │   │   ├── service/          # Business services
│   │   │   ├── repository/       # JPA repositories
│   │   │   ├── entity/           # JPA entities
│   │   │   ├── dto/              # Data transfer objects
│   │   │   ├── security/         # Authentication (phone verification)
│   │   │   └── integration/      # External service clients (OSRM)
│   │   └── resources/
│   │       ├── application.yml   # Configuration
│   │       └── db/migration/     # Flyway migrations
│   └── test/
│       └── java/com/navblind/server/
├── Dockerfile
└── pom.xml (or build.gradle.kts)

# ESP32-CAM Firmware
smartglass/
├── src/
│   ├── main.cpp              # Main entry point
│   ├── camera.cpp            # Camera initialization
│   ├── wifi_stream.cpp       # Wi-Fi video streaming
│   └── battery.cpp           # Battery monitoring
├── include/
└── platformio.ini

# Infrastructure
docker/
├── docker-compose.yml        # Local development stack
├── docker-compose.prod.yml   # Production configuration
├── nginx/
│   └── nginx.conf            # Reverse proxy configuration
├── osrm/
│   └── Dockerfile            # OSRM with Korea OSM data
└── init-scripts/
    └── init-db.sql           # PostgreSQL initialization
```

**Structure Decision**: Mobile + Backend API architecture with separate ESP32-CAM firmware. The Android app handles all ML processing on-device while the backend provides routing and user data services.

## Complexity Tracking

No constitution violations requiring justification. Architecture follows standard patterns for mobile + API projects.

## Constitution Check (Post-Design)

*Re-evaluated after Phase 1 design completion.*

| Principle | Status | Notes |
|-----------|--------|-------|
| Modular Architecture | PASS | Clear 3-tier: ESP32-CAM → Android App → Backend |
| Testability | PASS | Unit tests per component, integration tests with TestContainers |
| Simplicity | PASS | Standard REST API, established ML framework (TFLite) |
| Documentation | PASS | OpenAPI contract, data model, quickstart guide complete |
| Security | PASS | Firebase phone auth, JWT tokens, HTTPS via Nginx |

**Post-Design Gate Status**: PASSED

## Phase Completion Status

| Phase | Status | Artifacts |
|-------|--------|-----------|
| Phase 0: Research | COMPLETE | `research.md` |
| Phase 1: Design | COMPLETE | `data-model.md`, `contracts/api.yaml`, `quickstart.md` |
| Phase 2: Tasks | COMPLETE | `tasks.md` (159 tasks) |

## Next Steps

1. Review tasks in `tasks.md` and adjust priorities if needed
2. Begin implementation with `/speckit.implement` or manual development
3. Follow MVP strategy: US1 (Navigation) + US4 (Streaming) first
