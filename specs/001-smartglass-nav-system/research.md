# Research: Smart Glass Navigation System

**Date**: 2026-01-30
**Feature**: 001-smartglass-nav-system

## 1. YOLO Object Detection on Android

### Decision
Use YOLOv8n (nano) model with TensorFlow Lite for on-device inference.

### Rationale
- YOLOv8n offers best balance of speed and accuracy for mobile devices
- TensorFlow Lite provides optimized Android inference with GPU delegate support
- Model size ~6MB suitable for mobile deployment
- Achieves 15-30 FPS on mid-range Android devices with GPU acceleration

### Alternatives Considered
| Alternative | Rejected Because |
|-------------|------------------|
| YOLOv8s/m/l | Too slow for real-time mobile inference (>200ms) |
| MobileNet SSD | Lower accuracy for small objects like curbs, poles |
| MediaPipe | Limited custom object class support |
| Server-side detection | Network latency violates <2s requirement |

### Implementation Notes
- Use TFLite GPU delegate for acceleration
- Pre-process frames to 640x640 for YOLO input
- Post-process with NMS (Non-Maximum Suppression)
- Custom training for Korean-specific obstacles (공사 표지판, 점자블록)

---

## 2. GPS Error Correction with ARCore Geospatial + IMU + Visual Odometry

### Decision
Implement sensor fusion using Kalman Filter combining:
1. ARCore Geospatial API (VPS - Visual Positioning System)
2. Android IMU sensors (accelerometer + gyroscope)
3. Visual odometry from YOLO detection matching with OSM features

### Rationale
- ARCore Geospatial provides sub-meter accuracy in supported areas using Google's VPS
- IMU provides continuous position updates between GPS fixes
- Visual odometry using detected landmarks (crosswalks, signs) matched with OSM improves accuracy
- Kalman Filter optimally fuses uncertain measurements from multiple sources

### Sensor Fusion Architecture
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ GPS/ARCore  │     │ IMU Sensors │     │ Visual Odom │
│ (1-5m acc)  │     │ (drift)     │     │ (OSM match) │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           │
                    ┌──────▼──────┐
                    │ Kalman Filter│
                    │ (Extended)   │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ Fused Position│
                    │ (<5m accuracy)│
                    └─────────────┘
```

### Alternatives Considered
| Alternative | Rejected Because |
|-------------|------------------|
| GPS only | 10-30m urban canyon error unacceptable |
| RTK GPS | Requires external hardware, not consumer-friendly |
| Wi-Fi fingerprinting | Requires pre-mapping, inconsistent coverage |
| Bluetooth beacons | Requires infrastructure deployment |

### Implementation Notes
- ARCore Geospatial requires Google Play Services
- Fallback to IMU + GPS when ARCore unavailable
- Visual odometry uses detected crosswalks, traffic lights as landmarks
- Match detected features with OSM POI data for position correction

---

## 3. Object Distance Estimation and Trajectory Prediction

### Decision
Use monocular depth estimation combined with object tracking for distance and trajectory.

### Rationale
- Single camera (ESP32-CAM) cannot provide stereo depth
- MiDaS or similar lightweight depth models provide relative depth
- Object size + known reference objects enable distance estimation
- Multi-frame tracking enables velocity and trajectory prediction

### Distance Estimation Methods
1. **Reference Object Method**: Use known-size objects (traffic lights ~30cm, pedestrians ~170cm) for scale
2. **Ground Plane Assumption**: Objects touching ground → use camera height and angle
3. **Learned Depth**: Lightweight depth estimation model for relative ordering

### Trajectory Prediction
```
Frame t-2    Frame t-1    Frame t      Predicted
    □            □           □    →      □ (future position)
    │            │           │
    └────────────┴───────────┘
         Calculate velocity vector
```

### Implementation Notes
- Track objects across frames using DeepSORT or ByteTrack
- Calculate velocity from position delta / time delta
- Predict collision risk based on trajectory intersection
- Prioritize warnings by: proximity × velocity × trajectory overlap

---

## 4. ESP32-CAM Video Streaming Protocol

### Decision
Use MJPEG streaming over HTTP with WebSocket control channel.

### Rationale
- MJPEG is natively supported by ESP32-CAM libraries
- Lower latency than H.264 encoding (no encode delay)
- Simple implementation with esp32-camera library
- WebSocket provides bidirectional control (start/stop, quality adjust)

### Protocol Design
```
ESP32-CAM                              Android App
    │                                      │
    │◄────── WebSocket Control ──────────►│
    │   (connect, quality, status)         │
    │                                      │
    │────── HTTP MJPEG Stream ───────────►│
    │   (continuous JPEG frames)           │
    │                                      │
```

### Streaming Parameters
- Resolution: 640x480 (QVGA) for detection, adjustable
- Frame rate: 10-15 FPS target
- JPEG quality: 60-80% (bandwidth vs quality tradeoff)
- Wi-Fi: Direct connection (AP mode) or same network (STA mode)

### Alternatives Considered
| Alternative | Rejected Because |
|-------------|------------------|
| H.264/RTSP | ESP32 encoding too slow, adds latency |
| Bluetooth video | Bandwidth insufficient for video |
| USB OTG | Requires physical cable connection |
| Raw frames | Bandwidth too high (30MB/s uncompressed) |

---

## 5. OSRM Routing Integration

### Decision
Self-host OSRM with Korea OSM extract, expose via backend REST API.

### Rationale
- OSRM provides fast pedestrian routing with OSM data
- Self-hosting eliminates API costs and rate limits
- Can customize pedestrian profile for accessibility features
- Docker deployment simplifies setup and scaling

### Architecture
```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Android App │────►│ Spring Boot │────►│ OSRM Server │
│             │     │ Backend     │     │ (Docker)    │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
                    ┌──────▼──────┐
                    │ PostgreSQL  │
                    │ (User Data) │
                    └─────────────┘
```

### OSRM Setup
1. Download Korea OSM extract from Geofabrik
2. Process with osrm-extract, osrm-partition, osrm-customize
3. Run osrm-routed in Docker container
4. Backend proxies routing requests and adds user preferences

### Pedestrian Profile Customization
- Prefer sidewalks over roads
- Avoid stairs (accessibility preference)
- Prefer signalized crosswalks
- Weight by sidewalk width/quality from OSM tags

---

## 6. Phone Number Authentication (SMS/Voice)

### Decision
Use Firebase Authentication with phone number sign-in.

### Rationale
- Firebase provides turnkey phone auth with SMS and voice verification
- Handles carrier compatibility, rate limiting, fraud prevention
- Free tier sufficient for development (10K verifications/month)
- Easy integration with Android via Firebase SDK

### Authentication Flow
```
1. User enters phone number (voice input)
2. App requests verification code from Firebase
3. Firebase sends SMS or voice call with code
4. User speaks verification code
5. App verifies code with Firebase
6. Backend receives Firebase ID token
7. Backend creates/retrieves user session
```

### Alternatives Considered
| Alternative | Rejected Because |
|-------------|------------------|
| Custom SMS gateway | Complex carrier integration, higher cost |
| Email/password | Difficult for visually impaired (typing) |
| OAuth social login | Requires visual interaction with provider |
| Device-based auth | No cross-device sync, recovery issues |

---

## 7. Korean Speech Recognition and TTS

### Decision
Use Android native Speech Recognition (Google) + TTS for Korean language.

### Rationale
- Android Speech Recognition supports Korean with high accuracy
- No additional SDK or API costs
- Works offline with downloaded language pack
- TTS quality sufficient for navigation instructions

### Implementation
- `SpeechRecognizer` API for voice input
- `TextToSpeech` API for spoken output
- Korean language pack: "ko-KR"
- Custom vocabulary for navigation terms

### Voice Command Grammar
```
Destinations: "[장소명]으로 안내해줘", "[장소명] 가자"
Controls: "멈춰", "다시 말해줘", "경로 취소"
Queries: "지금 어디야?", "도착까지 얼마나?"
```

---

## 8. Redis Caching Strategy

### Decision
Use Redis for session caching, route caching, and async job queue.

### Rationale
- Low latency cache for frequently accessed user data
- Route caching reduces OSRM load for repeated routes
- Pub/Sub for real-time updates (route changes, alerts)
- Redis Streams for async job processing

### Cache Structure
```
user:{userId}:session     # Session data (TTL: 24h)
user:{userId}:preferences # User preferences (TTL: 7d)
route:{hash}              # Cached routes (TTL: 1h)
osm:tile:{z}:{x}:{y}      # OSM tile cache (TTL: 24h)
```

### Use Cases
1. **Session Cache**: Fast lookup of authenticated user
2. **Route Cache**: Avoid recalculating same routes
3. **Reroute Queue**: Async processing of reroute requests
4. **User Position Pub/Sub**: Real-time position tracking (future feature)

---

## 9. Nginx Reverse Proxy Configuration

### Decision
Nginx as reverse proxy with SSL termination, rate limiting, and load balancing.

### Configuration Highlights
```nginx
upstream backend {
    server spring-boot:8080;
}

server {
    listen 443 ssl;

    # SSL termination
    ssl_certificate /etc/ssl/cert.pem;
    ssl_certificate_key /etc/ssl/key.pem;

    # Rate limiting
    limit_req zone=api burst=20 nodelay;

    # API proxy
    location /api/ {
        proxy_pass http://backend/;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # WebSocket support (future)
    location /ws/ {
        proxy_pass http://backend/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

---

## 10. Docker Compose Stack

### Decision
Multi-container stack with Docker Compose for local development.

### Stack Components
```yaml
services:
  backend:
    build: ./backend
    depends_on: [postgres, redis, osrm]

  postgres:
    image: postgres:16
    volumes: [postgres-data:/var/lib/postgresql/data]

  redis:
    image: redis:7-alpine

  osrm:
    build: ./docker/osrm
    volumes: [osm-data:/data]

  nginx:
    image: nginx:alpine
    ports: ["80:80", "443:443"]
    depends_on: [backend]
```

### Data Volumes
- `postgres-data`: Persistent user database
- `osm-data`: Preprocessed OSRM routing data
- `redis-data`: Optional persistence for cache

---

## Summary of Key Decisions

| Area | Decision | Key Benefit |
|------|----------|-------------|
| Object Detection | YOLOv8n + TFLite | Real-time on-device, <2s latency |
| GPS Correction | ARCore + IMU + Visual Odometry | <5m accuracy in urban areas |
| Distance Estimation | Monocular depth + reference objects | Works with single camera |
| Video Streaming | MJPEG over HTTP | Low latency, simple implementation |
| Routing | Self-hosted OSRM | No API costs, customizable |
| Authentication | Firebase Phone Auth | Accessible for visually impaired |
| Voice I/O | Android native APIs | Offline capable, free |
| Caching | Redis | Fast session/route lookup |
| Proxy | Nginx | SSL, rate limiting, scaling |
| Deployment | Docker Compose | Reproducible, portable |
