# Tasks: Smart Glass Navigation System for Visually Impaired Users

**Input**: Design documents from `/specs/001-smartglass-nav-system/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/api.yaml

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Backend**: `backend/src/main/java/com/navblind/server/`
- **Android**: `android/app/src/main/java/com/navblind/`
- **ESP32**: `smartglass/src/`
- **Infrastructure**: `docker/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure for all three components

### Backend Setup
- [ ] T001 [P] Create Spring Boot project structure with Gradle in backend/build.gradle.kts
- [ ] T002 [P] Configure application.yml with PostgreSQL, Redis, OSRM settings in backend/src/main/resources/application.yml
- [ ] T003 [P] Add Spring Boot dependencies (Web, JPA, Security, Redis) in backend/build.gradle.kts
- [ ] T004 [P] Create Dockerfile for Spring Boot application in backend/Dockerfile

### Android Setup
- [ ] T005 [P] Create Android project structure with Kotlin in android/
- [ ] T006 [P] Configure build.gradle.kts with dependencies (TFLite, ARCore, Retrofit, Room, Hilt) in android/app/build.gradle.kts
- [ ] T007 [P] Setup Firebase project and add google-services.json to android/app/
- [ ] T008 [P] Configure AndroidManifest.xml with required permissions (camera, location, microphone, internet) in android/app/src/main/AndroidManifest.xml

### ESP32-CAM Setup
- [ ] T009 [P] Create PlatformIO project structure in smartglass/platformio.ini
- [ ] T010 [P] Configure ESP32-CAM board settings and dependencies in smartglass/platformio.ini

### Infrastructure Setup
- [ ] T011 [P] Create docker-compose.yml with PostgreSQL, Redis, OSRM, Nginx services in docker/docker-compose.yml
- [ ] T012 [P] Create Nginx reverse proxy configuration in docker/nginx/nginx.conf
- [ ] T013 [P] Create OSRM Dockerfile with Korea OSM data setup in docker/osrm/Dockerfile
- [ ] T014 [P] Create PostgreSQL initialization script in docker/init-scripts/init-db.sql

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**CRITICAL**: No user story work can begin until this phase is complete

### Backend Foundation
- [ ] T015 Create User entity with JPA annotations in backend/src/main/java/com/navblind/server/entity/User.java
- [ ] T016 Create UserRepository interface in backend/src/main/java/com/navblind/server/repository/UserRepository.java
- [ ] T017 [P] Create base DTO classes (ErrorResponse, etc.) in backend/src/main/java/com/navblind/server/dto/
- [ ] T018 [P] Configure Spring Security with Firebase token validation in backend/src/main/java/com/navblind/server/security/FirebaseAuthFilter.java
- [ ] T019 [P] Create SecurityConfig with JWT bearer authentication in backend/src/main/java/com/navblind/server/config/SecurityConfig.java
- [ ] T020 [P] Configure Redis connection and cache manager in backend/src/main/java/com/navblind/server/config/RedisConfig.java
- [ ] T021 [P] Create global exception handler in backend/src/main/java/com/navblind/server/config/GlobalExceptionHandler.java
- [ ] T022 Create Flyway migration V1__initial_schema.sql in backend/src/main/resources/db/migration/V1__initial_schema.sql

### Android Foundation
- [ ] T023 Setup Hilt dependency injection modules in android/app/src/main/java/com/navblind/di/AppModule.kt
- [ ] T024 [P] Create base domain models (User, Coordinate, etc.) in android/app/src/main/java/com/navblind/domain/model/
- [ ] T025 [P] Configure Retrofit API client with base URL in android/app/src/main/java/com/navblind/data/remote/ApiClient.kt
- [ ] T026 [P] Setup Room database configuration in android/app/src/main/java/com/navblind/data/local/AppDatabase.kt
- [ ] T027 [P] Create Firebase Auth wrapper service in android/app/src/main/java/com/navblind/service/auth/FirebaseAuthService.kt
- [ ] T028 [P] Create base TTS service for Korean voice output in android/app/src/main/java/com/navblind/service/voice/TextToSpeechService.kt
- [ ] T029 [P] Create base Speech Recognition service for Korean input in android/app/src/main/java/com/navblind/service/voice/SpeechRecognitionService.kt

### ESP32-CAM Foundation
- [ ] T030 Create main.cpp with setup and loop structure in smartglass/src/main.cpp
- [ ] T031 [P] Implement camera initialization in smartglass/src/camera.cpp
- [ ] T032 [P] Implement Wi-Fi connection manager in smartglass/src/wifi_manager.cpp

**Checkpoint**: Foundation ready - user story implementation can now begin

---

## Phase 3: User Story 1 - Voice-Based Destination Input and Route Guidance (Priority: P1)

**Goal**: User speaks destination, receives turn-by-turn voice navigation instructions

**Independent Test**: Speak "경복궁으로 안내해줘" → Receive voice navigation with first instruction within 5 seconds

### Backend - US1
- [ ] T033 [P] [US1] Create Destination entity in backend/src/main/java/com/navblind/server/entity/Destination.java
- [ ] T034 [P] [US1] Create NavigationSession entity in backend/src/main/java/com/navblind/server/entity/NavigationSession.java
- [ ] T035 [P] [US1] Create RouteRequest/RouteResponse DTOs in backend/src/main/java/com/navblind/server/dto/RouteDto.java
- [ ] T036 [P] [US1] Create DestinationRepository interface in backend/src/main/java/com/navblind/server/repository/DestinationRepository.java
- [ ] T037 [P] [US1] Create NavigationSessionRepository interface in backend/src/main/java/com/navblind/server/repository/NavigationSessionRepository.java
- [ ] T038 [US1] Create OSRM client for routing requests in backend/src/main/java/com/navblind/server/integration/OsrmClient.java
- [ ] T039 [US1] Implement NavigationService with route calculation in backend/src/main/java/com/navblind/server/service/NavigationService.java
- [ ] T040 [US1] Implement DestinationService with search functionality in backend/src/main/java/com/navblind/server/service/DestinationService.java
- [ ] T041 [US1] Create NavigationController with /navigation/route endpoint in backend/src/main/java/com/navblind/server/controller/NavigationController.java
- [ ] T042 [US1] Create DestinationController with /destinations/search endpoint in backend/src/main/java/com/navblind/server/controller/DestinationController.java
- [ ] T043 [US1] Implement route caching with Redis in backend/src/main/java/com/navblind/server/service/RouteCacheService.java

### Android - US1
- [ ] T044 [P] [US1] Create Route domain model in android/app/src/main/java/com/navblind/domain/model/Route.kt
- [ ] T045 [P] [US1] Create Instruction domain model in android/app/src/main/java/com/navblind/domain/model/Instruction.kt
- [ ] T046 [P] [US1] Create NavigationApi Retrofit interface in android/app/src/main/java/com/navblind/data/remote/NavigationApi.kt
- [ ] T047 [P] [US1] Create DestinationApi Retrofit interface in android/app/src/main/java/com/navblind/data/remote/DestinationApi.kt
- [ ] T048 [US1] Create NavigationRepository interface in android/app/src/main/java/com/navblind/domain/repository/NavigationRepository.kt
- [ ] T049 [US1] Implement NavigationRepositoryImpl in android/app/src/main/java/com/navblind/data/repository/NavigationRepositoryImpl.kt
- [ ] T050 [US1] Create StartNavigationUseCase in android/app/src/main/java/com/navblind/domain/usecase/StartNavigationUseCase.kt
- [ ] T051 [US1] Create GetNextInstructionUseCase in android/app/src/main/java/com/navblind/domain/usecase/GetNextInstructionUseCase.kt
- [ ] T052 [US1] Implement VoiceInputService for destination recognition in android/app/src/main/java/com/navblind/service/voice/VoiceInputService.kt
- [ ] T053 [US1] Implement NavigationGuidanceService for voice turn-by-turn output in android/app/src/main/java/com/navblind/service/voice/NavigationGuidanceService.kt
- [ ] T054 [US1] Implement InstructionToSpeechConverter for Korean-friendly messages in android/app/src/main/java/com/navblind/service/voice/InstructionToSpeechConverter.kt
- [ ] T055 [US1] Create NavigationViewModel in android/app/src/main/java/com/navblind/presentation/navigation/NavigationViewModel.kt
- [ ] T056 [US1] Create NavigationScreen UI (minimal, voice-focused) in android/app/src/main/java/com/navblind/presentation/navigation/NavigationScreen.kt
- [ ] T057 [US1] Implement voice command parser for navigation commands in android/app/src/main/java/com/navblind/service/voice/VoiceCommandParser.kt

**Checkpoint**: User Story 1 complete - Voice navigation from destination input to turn-by-turn guidance works independently

---

## Phase 4: User Story 2 - Real-Time Obstacle and Hazard Detection (Priority: P1)

**Goal**: Detect obstacles via smart glasses camera, provide voice alerts with action guidance

**Independent Test**: Walk with smart glasses, receive voice alert "전방 2미터에 장애물" within 2 seconds of detection

### ESP32-CAM - US2
- [ ] T058 [US2] Implement MJPEG streaming server in smartglass/src/wifi_stream.cpp
- [ ] T059 [US2] Add HTTP endpoint for video stream /stream in smartglass/src/wifi_stream.cpp
- [ ] T060 [US2] Implement WebSocket control channel for start/stop in smartglass/src/wifi_stream.cpp

### Android - US2
- [ ] T061 [P] [US2] Create DetectedObject domain model in android/app/src/main/java/com/navblind/domain/model/DetectedObject.kt
- [ ] T062 [P] [US2] Create ObjectDetectionResult model in android/app/src/main/java/com/navblind/domain/model/ObjectDetectionResult.kt
- [ ] T063 [US2] Implement ESP32CamStreamReceiver for MJPEG parsing in android/app/src/main/java/com/navblind/service/streaming/ESP32CamStreamReceiver.kt
- [ ] T064 [US2] Implement YoloObjectDetector with TFLite in android/app/src/main/java/com/navblind/service/detection/YoloObjectDetector.kt
- [ ] T065 [US2] Add YOLOv8n TFLite model file to android/app/src/main/assets/yolov8n.tflite
- [ ] T066 [US2] Implement ObjectTracker for multi-frame tracking in android/app/src/main/java/com/navblind/service/detection/ObjectTracker.kt
- [ ] T067 [US2] Implement DistanceEstimator for monocular depth in android/app/src/main/java/com/navblind/service/detection/DistanceEstimator.kt
- [ ] T068 [US2] Implement TrajectoryPredictor for collision risk in android/app/src/main/java/com/navblind/service/detection/TrajectoryPredictor.kt
- [ ] T069 [US2] Implement HazardPrioritizer for alert ordering in android/app/src/main/java/com/navblind/service/detection/HazardPrioritizer.kt
- [ ] T070 [US2] Create ObstacleAlertService for voice warnings in android/app/src/main/java/com/navblind/service/voice/ObstacleAlertService.kt
- [ ] T071 [US2] Implement DetectionToSpeechConverter for Korean alerts in android/app/src/main/java/com/navblind/service/voice/DetectionToSpeechConverter.kt
- [ ] T072 [US2] Create DetectionViewModel in android/app/src/main/java/com/navblind/presentation/navigation/DetectionViewModel.kt
- [ ] T073 [US2] Integrate detection overlay in NavigationScreen in android/app/src/main/java/com/navblind/presentation/navigation/NavigationScreen.kt

**Checkpoint**: User Story 2 complete - Obstacle detection and voice alerts work independently

---

## Phase 5: User Story 3 - Automatic Route Recalculation (Priority: P2)

**Goal**: Detect route deviation and automatically recalculate new route with voice notification

**Independent Test**: Deviate 15+ meters from route, hear "경로를 재탐색합니다" and receive new instructions within 3 seconds

**Note**: Can be tested independently using mock route data. Full integration requires US1.

### Backend - US3
- [ ] T074 [P] [US3] Create RerouteRequest/Response DTOs in backend/src/main/java/com/navblind/server/dto/RerouteDto.java
- [ ] T075 [US3] Add reroute endpoint to NavigationController in backend/src/main/java/com/navblind/server/controller/NavigationController.java
- [ ] T076 [US3] Implement reroute logic in NavigationService in backend/src/main/java/com/navblind/server/service/NavigationService.java
- [ ] T077 [US3] Add session reroute_count tracking in NavigationSessionRepository

### Android - US3
- [ ] T078 [US3] Create RouteDeviationDetector service in android/app/src/main/java/com/navblind/service/location/RouteDeviationDetector.kt
- [ ] T079 [US3] Implement deviation threshold logic (15m) in RouteDeviationDetector
- [ ] T080 [US3] Create RerouteUseCase in android/app/src/main/java/com/navblind/domain/usecase/RerouteUseCase.kt
- [ ] T081 [US3] Add reroute API call to NavigationRepositoryImpl
- [ ] T082 [US3] Implement automatic reroute trigger in NavigationViewModel
- [ ] T083 [US3] Add "경로를 재탐색합니다" voice announcement in NavigationGuidanceService

**Checkpoint**: User Story 3 complete - Automatic rerouting works independently

---

## Phase 6: User Story 4 - Smart Glasses Video Streaming Connection (Priority: P2)

**Goal**: Pair ESP32-CAM smart glasses with mobile app, establish stable video streaming

**Independent Test**: Initiate pairing, video stream starts within 10 seconds, reconnection on disconnect

### Backend - US4
- [ ] T084 [P] [US4] Create SmartGlasses entity in backend/src/main/java/com/navblind/server/entity/SmartGlasses.java
- [ ] T085 [P] [US4] Create SmartGlassesRepository interface in backend/src/main/java/com/navblind/server/repository/SmartGlassesRepository.java
- [ ] T086 [P] [US4] Create Device DTOs (RegisterDeviceRequest, DeviceResponse) in backend/src/main/java/com/navblind/server/dto/DeviceDto.java
- [ ] T087 [US4] Implement DeviceService in backend/src/main/java/com/navblind/server/service/DeviceService.java
- [ ] T088 [US4] Create DeviceController with /devices endpoints in backend/src/main/java/com/navblind/server/controller/DeviceController.java

### ESP32-CAM - US4
- [ ] T089 [US4] Implement AP mode for direct connection in smartglass/src/wifi_manager.cpp
- [ ] T090 [US4] Implement STA mode for network connection in smartglass/src/wifi_manager.cpp
- [ ] T091 [US4] Add device ID generation and broadcast in smartglass/src/main.cpp
- [ ] T092 [US4] Implement connection status LED indicator in smartglass/src/main.cpp

### Android - US4
- [ ] T093 [P] [US4] Create SmartGlasses domain model in android/app/src/main/java/com/navblind/domain/model/SmartGlasses.kt
- [ ] T094 [P] [US4] Create DeviceApi Retrofit interface in android/app/src/main/java/com/navblind/data/remote/DeviceApi.kt
- [ ] T095 [US4] Create DeviceRepository in android/app/src/main/java/com/navblind/domain/repository/DeviceRepository.kt
- [ ] T096 [US4] Implement SmartGlassesConnectionService in android/app/src/main/java/com/navblind/service/streaming/SmartGlassesConnectionService.kt
- [ ] T097 [US4] Implement auto-reconnection logic in SmartGlassesConnectionService
- [ ] T098 [US4] Implement adaptive quality adjustment in ESP32CamStreamReceiver
- [ ] T099 [US4] Create DeviceSettingsViewModel in android/app/src/main/java/com/navblind/presentation/settings/DeviceSettingsViewModel.kt
- [ ] T100 [US4] Create DevicePairingScreen UI in android/app/src/main/java/com/navblind/presentation/settings/DevicePairingScreen.kt
- [ ] T101 [US4] Add connection status voice announcements in SmartGlassesConnectionService
- [ ] T101-A [US4] Implement battery status broadcast from ESP32-CAM in smartglass/src/battery.cpp
- [ ] T101-B [US4] Add battery level receiver to SmartGlassesConnectionService in android/app/src/main/java/com/navblind/service/streaming/SmartGlassesConnectionService.kt
- [ ] T101-C [US4] Add battery low voice warning in SmartGlassesConnectionService ("스마트글래스 배터리가 부족합니다")

**Checkpoint**: User Story 4 complete - Smart glasses pairing and streaming works independently

---

## Phase 7: User Story 5 - Enhanced GPS Accuracy (Priority: P2)

**Goal**: Achieve <5m GPS accuracy using ARCore Geospatial, IMU fusion, and visual odometry

**Independent Test**: Compare reported location with actual position in urban area, verify <5m error

### Android - US5
- [ ] T102 [P] [US5] Create FusedPosition domain model in android/app/src/main/java/com/navblind/domain/model/FusedPosition.kt
- [ ] T103 [US5] Implement ARCoreGeospatialService in android/app/src/main/java/com/navblind/service/location/ARCoreGeospatialService.kt
- [ ] T104 [US5] Implement IMUSensorService for accelerometer/gyroscope in android/app/src/main/java/com/navblind/service/location/IMUSensorService.kt
- [ ] T105 [US5] Implement VisualOdometryService using detected landmarks in android/app/src/main/java/com/navblind/service/location/VisualOdometryService.kt
- [ ] T106 [US5] Implement KalmanFilterFusion for sensor fusion in android/app/src/main/java/com/navblind/service/location/KalmanFilterFusion.kt
- [ ] T107 [US5] Create LocationFusionService that combines all sources in android/app/src/main/java/com/navblind/service/location/LocationFusionService.kt
- [ ] T108 [US5] Integrate fused location into NavigationViewModel
- [ ] T109 [US5] Add GPS quality indicator voice feedback in NavigationGuidanceService
- [ ] T110 [US5] Implement fallback to GPS-only when ARCore unavailable in LocationFusionService

**Checkpoint**: User Story 5 complete - Enhanced GPS accuracy works independently

---

## Phase 8: User Story 6 - User Account and Data Management (Priority: P3)

**Goal**: Phone auth registration, preferences storage, saved destinations across sessions

**Independent Test**: Register with phone number, save preferences and destinations, verify persistence after app restart

### Backend - US6
- [ ] T111 [P] [US6] Create Preference entity in backend/src/main/java/com/navblind/server/entity/Preference.java
- [ ] T112 [P] [US6] Create PreferenceRepository interface in backend/src/main/java/com/navblind/server/repository/PreferenceRepository.java
- [ ] T113 [P] [US6] Create User/Preference DTOs in backend/src/main/java/com/navblind/server/dto/UserDto.java
- [ ] T114 [US6] Implement UserService in backend/src/main/java/com/navblind/server/service/UserService.java
- [ ] T115 [US6] Implement PreferenceService in backend/src/main/java/com/navblind/server/service/PreferenceService.java
- [ ] T116 [US6] Create AuthController with /auth/verify endpoint in backend/src/main/java/com/navblind/server/controller/AuthController.java
- [ ] T117 [US6] Create UserController with /users/me endpoints in backend/src/main/java/com/navblind/server/controller/UserController.java
- [ ] T118 [US6] Add CRUD endpoints to DestinationController in backend/src/main/java/com/navblind/server/controller/DestinationController.java
- [ ] T119 [US6] Implement session caching in Redis in backend/src/main/java/com/navblind/server/service/SessionCacheService.java
- [ ] T120 [US6] Implement preference caching in Redis in PreferenceService

### Android - US6
- [ ] T121 [P] [US6] Create LocalDestination Room entity in android/app/src/main/java/com/navblind/data/local/entity/LocalDestination.kt
- [ ] T122 [P] [US6] Create LocalPreference Room entity in android/app/src/main/java/com/navblind/data/local/entity/LocalPreference.kt
- [ ] T123 [P] [US6] Create DestinationDao interface in android/app/src/main/java/com/navblind/data/local/dao/DestinationDao.kt
- [ ] T124 [P] [US6] Create PreferenceDao interface in android/app/src/main/java/com/navblind/data/local/dao/PreferenceDao.kt
- [ ] T125 [P] [US6] Create UserApi Retrofit interface in android/app/src/main/java/com/navblind/data/remote/UserApi.kt
- [ ] T126 [P] [US6] Create AuthApi Retrofit interface in android/app/src/main/java/com/navblind/data/remote/AuthApi.kt
- [ ] T127 [US6] Create UserRepository in android/app/src/main/java/com/navblind/domain/repository/UserRepository.kt
- [ ] T128 [US6] Implement UserRepositoryImpl with local+remote sync in android/app/src/main/java/com/navblind/data/repository/UserRepositoryImpl.kt
- [ ] T129 [US6] Create DestinationRepositoryImpl with sync in android/app/src/main/java/com/navblind/data/repository/DestinationRepositoryImpl.kt
- [ ] T130 [US6] Create LoginUseCase in android/app/src/main/java/com/navblind/domain/usecase/LoginUseCase.kt
- [ ] T131 [US6] Create SaveDestinationUseCase in android/app/src/main/java/com/navblind/domain/usecase/SaveDestinationUseCase.kt
- [ ] T132 [US6] Create UpdatePreferencesUseCase in android/app/src/main/java/com/navblind/domain/usecase/UpdatePreferencesUseCase.kt
- [ ] T133 [US6] Implement VoiceGuidedRegistrationService in android/app/src/main/java/com/navblind/service/voice/VoiceGuidedRegistrationService.kt
- [ ] T134 [US6] Create AuthViewModel in android/app/src/main/java/com/navblind/presentation/auth/AuthViewModel.kt
- [ ] T135 [US6] Create LoginScreen with voice guidance in android/app/src/main/java/com/navblind/presentation/auth/LoginScreen.kt
- [ ] T136 [US6] Create SettingsViewModel in android/app/src/main/java/com/navblind/presentation/settings/SettingsViewModel.kt
- [ ] T137 [US6] Create PreferencesScreen (voice-accessible) in android/app/src/main/java/com/navblind/presentation/settings/PreferencesScreen.kt
- [ ] T138 [US6] Create SavedDestinationsScreen in android/app/src/main/java/com/navblind/presentation/settings/SavedDestinationsScreen.kt

**Checkpoint**: User Story 6 complete - User accounts, preferences, and destinations work independently

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

### ESP32-CAM Polish
- [ ] T139 [P] Add battery percentage estimation algorithm in smartglass/src/battery.cpp (enhancement to T101-A)
- [ ] T140 [P] Add multiple battery warning levels (50%, 20%, 10%) in smartglass/src/battery.cpp
- [ ] T141 Implement power-saving mode when idle in smartglass/src/main.cpp

### Android Polish
- [ ] T142 [P] Implement offline mode detection and handling in android/app/src/main/java/com/navblind/service/NetworkStateService.kt
- [ ] T143 [P] Add battery optimization for background services in android/app/src/main/java/com/navblind/service/
- [ ] T144 [P] Implement accessibility service integration in android/app/src/main/java/com/navblind/service/AccessibilityService.kt
- [ ] T145 Create main app navigation graph in android/app/src/main/java/com/navblind/presentation/NavGraph.kt
- [ ] T146 Add vibration feedback for alerts in ObstacleAlertService
- [ ] T147 Implement multi-hazard priority queue announcement in ObstacleAlertService

### Backend Polish
- [ ] T148 [P] Add request rate limiting in backend/src/main/java/com/navblind/server/config/RateLimitConfig.java
- [ ] T149 [P] Add structured logging configuration in backend/src/main/resources/logback-spring.xml
- [ ] T150 [P] Add API documentation with SpringDoc OpenAPI in backend/src/main/java/com/navblind/server/config/OpenApiConfig.java

### Infrastructure Polish
- [ ] T151 [P] Create docker-compose.prod.yml for production in docker/docker-compose.prod.yml
- [ ] T152 [P] Add SSL certificate configuration to Nginx in docker/nginx/nginx.conf
- [ ] T153 Create deployment documentation in docs/deployment.md

### Validation
- [ ] T154 Run quickstart.md validation - verify all setup steps work
- [ ] T155 End-to-end test: Full navigation flow from voice input to arrival
- [ ] T156 Performance validation: Verify SC-001 through SC-010 success criteria

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies - can start immediately
- **Phase 2 (Foundational)**: Depends on Setup completion - BLOCKS all user stories
- **Phase 3-8 (User Stories)**: All depend on Foundational phase completion
  - US1 and US2 are both P1 (can run in parallel)
  - US3, US4, US5 are all P2 (can run in parallel after P1)
  - US6 is P3 (lowest priority)
- **Phase 9 (Polish)**: Depends on all desired user stories being complete

### User Story Dependencies

| Story | Priority | Can Start After | Dependencies on Other Stories |
|-------|----------|-----------------|-------------------------------|
| US1 (Navigation) | P1 | Foundational | None |
| US2 (Detection) | P1 | Foundational | None (uses same ESP32 setup) |
| US3 (Reroute) | P2 | Foundational | Can test independently with mock route data; integrates with US1 for full flow |
| US4 (Streaming) | P2 | Foundational | None (enables US2 but can test independently) |
| US5 (GPS) | P2 | Foundational | Enhances US1/US3 but works independently |
| US6 (Account) | P3 | Foundational | None |

### Within Each User Story

1. Backend entities and DTOs (parallel)
2. Backend repositories (parallel)
3. Backend services (sequential, depends on repos)
4. Backend controllers (depends on services)
5. Android domain models (parallel)
6. Android API interfaces (parallel)
7. Android repositories (depends on API interfaces)
8. Android use cases (depends on repositories)
9. Android services (depends on use cases)
10. Android ViewModels (depends on use cases/services)
11. Android UI (depends on ViewModels)

### Parallel Opportunities

**Setup Phase (T001-T014)**: All 14 tasks can run in parallel
**Foundational Phase (T015-T032)**:
- Backend: T017-T021 parallel
- Android: T024-T029 parallel
- ESP32: T031-T032 parallel

**Per User Story**:
- All [P] marked tasks within a story can run in parallel
- Entities/DTOs can run in parallel
- API interfaces can run in parallel

---

## Parallel Example: User Story 1

```bash
# Launch backend models/DTOs together:
Task: "T033 Create Destination entity"
Task: "T034 Create NavigationSession entity"
Task: "T035 Create RouteRequest/RouteResponse DTOs"
Task: "T036 Create DestinationRepository"
Task: "T037 Create NavigationSessionRepository"

# Launch Android models/APIs together:
Task: "T044 Create Route domain model"
Task: "T045 Create Instruction domain model"
Task: "T046 Create NavigationApi Retrofit interface"
Task: "T047 Create DestinationApi Retrofit interface"
```

---

## Implementation Strategy

### MVP First (User Stories 1 + 4)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 6: User Story 4 (Streaming) - Need glasses connected first
4. Complete Phase 3: User Story 1 (Navigation) - Core functionality
5. **STOP and VALIDATE**: Test voice navigation end-to-end
6. Deploy/demo if ready

### Recommended Order for Single Developer

1. Setup (Phase 1) - All components
2. Foundational (Phase 2) - All components
3. **US4** (Streaming) - Get hardware working first
4. **US1** (Navigation) - Core value delivery
5. **US2** (Detection) - Safety feature
6. **US3** (Reroute) - Enhances navigation
7. **US5** (GPS) - Quality improvement
8. **US6** (Accounts) - Personalization
9. Polish (Phase 9)

### Incremental Delivery

| Milestone | Stories | Value Delivered |
|-----------|---------|-----------------|
| MVP | US1 + US4 | Voice navigation with smart glasses streaming |
| Safety | + US2 | Real-time obstacle detection and alerts |
| Robustness | + US3 + US5 | Auto-reroute + Enhanced GPS accuracy |
| Full Product | + US6 + Polish | User accounts, preferences, production-ready |

---

## Summary

| Phase | Task Count | Description |
|-------|------------|-------------|
| Phase 1: Setup | 14 | Project initialization |
| Phase 2: Foundational | 18 | Core infrastructure |
| Phase 3: US1 Navigation | 25 | Voice destination + routing |
| Phase 4: US2 Detection | 16 | Obstacle detection + alerts |
| Phase 5: US3 Reroute | 10 | Automatic recalculation |
| Phase 6: US4 Streaming | 21 | Smart glasses connection + battery status |
| Phase 7: US5 GPS | 9 | Enhanced location accuracy |
| Phase 8: US6 Account | 28 | User management |
| Phase 9: Polish | 18 | Cross-cutting improvements |
| **Total** | **159** | |

---

## Notes

- [P] tasks = different files, no dependencies - can run in parallel
- [US#] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- MVP scope: US1 (Navigation) + US4 (Streaming) = 43 tasks after foundational
