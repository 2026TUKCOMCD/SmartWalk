# Quickstart Guide: Smart Glass Navigation System

## Prerequisites

### Development Environment
- **Java**: JDK 21
- **Kotlin**: 1.9+
- **Gradle**: 8.10+ (Android), 8.5+ (Backend)
- **Android Studio**: Hedgehog (2023.1.1) or later
- **Docker**: 24.0+ with Docker Compose
- **PlatformIO**: For ESP32-CAM firmware development
- **Git**: 2.40+

### Hardware
- Android phone (API 26+, with ARCore support recommended)
- ESP32-CAM module
- USB-to-Serial adapter (for ESP32 programming)

### Accounts & API Keys
- **Firebase Project**: Phone authentication enabled
- **Google Cloud**: ARCore Geospatial API enabled (optional, for enhanced GPS)

---

## 1. Clone Repository

```bash
git clone <repository-url>
cd capstoneDesign
git checkout 001-smartglass-nav-system
```

---

## 2. Backend Setup

### 2.1 Start Docker Stack

```bash
cd docker

# Download Korea OSM data for OSRM (first time only, ~500MB)
./scripts/download-osm.sh

# Start all services
docker-compose up -d
```

Services started:
- **PostgreSQL**: localhost:5432
- **Redis**: localhost:6379
- **OSRM**: localhost:5000
- **Nominatim**: localhost:8088 (geocoding/search)
- **Spring Boot API**: localhost:8080
- **Nginx**: localhost:80/443

### 2.2 Configure Application

Create `backend/src/main/resources/application-local.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/navblind
    username: navblind
    password: navblind_dev
  redis:
    host: localhost
    port: 6379

firebase:
  credentials-path: ${FIREBASE_CREDENTIALS_PATH}

osrm:
  base-url: http://localhost:5000

nominatim:
  base-url: http://localhost:8088
```

### 2.3 Run Backend (Development)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
```

Verify: `curl http://localhost:8080/actuator/health`

---

## 3. Android App Setup

### 3.1 Open Project

1. Open Android Studio
2. File → Open → Select `android/` directory
3. Wait for Gradle sync

### 3.2 Configure Firebase

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Create project or select existing
3. Add Android app with package name `com.navblind`
4. Download `google-services.json`
5. Place in `android/app/google-services.json`
6. Enable Phone Authentication in Firebase Auth

### 3.3 Configure API Endpoint

Edit `android/app/src/main/res/values/config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="api_base_url">http://10.0.2.2:8080/v1</string>
    <!-- Use 10.0.2.2 for emulator, actual IP for physical device -->
</resources>
```

### 3.4 Build and Run

```bash
cd android
./gradlew assembleDebug

# Or run from Android Studio with connected device/emulator
```

---

## 4. ESP32-CAM Setup

### 4.1 Install PlatformIO

```bash
pip install platformio
# Or install PlatformIO IDE extension in VS Code
```

### 4.2 Configure Wi-Fi

Edit `smartglass/include/config.h`:

```cpp
#define WIFI_SSID "YourWiFiSSID"
#define WIFI_PASSWORD "YourWiFiPassword"

// Or use AP mode for direct connection
#define USE_AP_MODE true
#define AP_SSID "NavBlind-Glasses"
#define AP_PASSWORD "navblind123"
```

### 4.3 Flash Firmware

```bash
cd smartglass
pio run --target upload

# Monitor serial output
pio device monitor
```

### 4.4 Verify Streaming

1. Connect to ESP32-CAM Wi-Fi (if AP mode) or same network
2. Open browser: `http://<ESP32-IP>/stream`
3. Should see MJPEG video stream

---

## 5. Development Workflow

### Backend Development

```bash
# Run tests
cd backend
./gradlew test

# Run with hot reload
./gradlew bootRun --args='--spring.profiles.active=local' --continuous

# Generate API docs
./gradlew generateOpenApiDocs
```

### Android Development

```bash
# Run unit tests
cd android
./gradlew testDebugUnitTest

# Run instrumented tests (requires device)
./gradlew connectedDebugAndroidTest

# Lint check
./gradlew lintDebug
```

### ESP32 Development

```bash
cd smartglass

# Build only
pio run

# Upload and monitor
pio run --target upload && pio device monitor
```

---

## 6. Testing the Full System

### Step 1: Start Backend
```bash
cd docker && docker-compose up -d
```

### Step 2: Start ESP32-CAM
Power on the ESP32-CAM module and verify Wi-Fi connection.

### Step 3: Run Android App
1. Install app on Android device
2. Register with phone number
3. Pair with smart glasses (Settings → Devices → Add)
4. Speak a destination: "경복궁으로 안내해줘"
5. Walk with glasses and observe obstacle alerts

### Verification Checklist
- [ ] Backend health check passes
- [ ] Firebase authentication works
- [ ] ESP32-CAM streams video to app
- [ ] YOLO detects objects (check logs)
- [ ] Route calculation returns path
- [ ] Voice guidance speaks instructions

---

## 7. Troubleshooting

### Backend Issues

| Problem | Solution |
|---------|----------|
| Database connection failed | Check PostgreSQL container: `docker-compose logs postgres` |
| OSRM returns no route | Verify OSM data loaded: `docker-compose logs osrm` |
| Nominatim search fails | Check import completed: `docker-compose logs nominatim` (initial import ~30min) |
| Redis connection refused | Ensure Redis container running: `docker ps` |

### Android Issues

| Problem | Solution |
|---------|----------|
| Firebase auth fails | Check `google-services.json` placement and SHA-1 fingerprint in Firebase |
| Cannot connect to backend | Verify `api_base_url` and network connectivity |
| ARCore not available | Use device with ARCore support or test without GPS enhancement |
| YOLO model load fails | Ensure `yolov8n.tflite` in `assets/` folder |

### ESP32-CAM Issues

| Problem | Solution |
|---------|----------|
| Camera init failed | Check camera ribbon cable connection |
| Wi-Fi won't connect | Verify SSID/password, try AP mode |
| Stream laggy | Reduce resolution in `camera.cpp` (QVGA recommended) |
| Battery drains fast | Lower frame rate, reduce JPEG quality |

---

## 8. Project Structure Quick Reference

```
capstoneDesign/
├── android/           # Android app (Kotlin)
│   └── app/src/main/java/com/navblind/
│       ├── service/detection/    # YOLO object detection
│       ├── service/location/     # GPS + ARCore fusion
│       └── service/voice/        # TTS and speech recognition
├── backend/           # Spring Boot server (Java 21)
│   └── src/main/java/com/navblind/server/
│       ├── controller/           # REST endpoints
│       ├── service/              # Business logic
│       └── integration/          # OSRM client
├── smartglass/        # ESP32-CAM firmware (C++)
│   └── src/
│       ├── main.cpp              # Entry point
│       └── wifi_stream.cpp       # MJPEG streaming
├── docker/            # Infrastructure
│   ├── docker-compose.yml
│   └── nginx/nginx.conf
└── specs/001-smartglass-nav-system/
    ├── spec.md                   # Feature specification
    ├── plan.md                   # Implementation plan
    ├── research.md               # Technical research
    ├── data-model.md             # Database schema
    └── contracts/api.yaml        # OpenAPI spec
```

---

## 9. Next Steps

After completing the quickstart:

1. **Run `/speckit.tasks`** to generate implementation tasks
2. Review tasks in `specs/001-smartglass-nav-system/tasks.md`
3. Begin implementation following task priority order
4. Use `/speckit.implement` to execute tasks with AI assistance
