# NavBlind 서비스 흐름도 (시나리오)

> **기준**: spec 완전 구현 시 최종 서비스 흐름
> **용도**: 데모 발표용 시나리오 흐름도

---

## 1. 사용 주체 및 시스템 관계도

```mermaid
graph LR
    %% ── 사용 주체 ──
    USER["👤 시각장애인 사용자, 음성 명령으로만 조작, 화면 비의존 UX"]
    OPERATOR["🧑‍💻 시스템 운영자, 서버 배포 및 인프라 관리"]

    %% ── 수행 범위 ──
    subgraph SCOPE["── 수행 범위 ──"]
        subgraph HW["하드웨어"]
            SG["📷 스마트글래스, ESP32-CAM, MJPEG 영상 송출"]
            APP["📱 Android 앱, 음성 입출력 · 객체 검출, 위치 추적 · 경로 안내"]
        end
        subgraph SERVER["백엔드 서버 (Docker Compose)"]
            BE["⚙️ REST API 서버, Spring Boot 3.5"]
            DB["🗄 PostgreSQL 16, 사용자 · 세션 · 목적지"]
            CACHE["⚡ Redis 7, 세션 · 경로 캐시"]
            ROUTING["🗺 OSRM + Nominatim, 경로 계산 · 지오코딩 (self-hosted)"]
        end
    end

    %% ── 외부 범위 ──
    subgraph EXT["── 외부 범위 ──"]
        FIREBASE["🔑 Firebase Auth, SMS 인증"]
        ARCORE["📡 ARCore Geospatial API, Google VPS"]
        OSM["🗺 OpenStreetMap, 공개 지도 데이터"]
        ANDROID_API["🎤 Android STT / TTS API, 음성 인식 · 합성"]
        GPS_API["🛰 FusedLocationProvider, GPS · Wi-Fi · Cell"]
    end

    %% ── 관계 ──
    USER -->|착용| SG
    USER -->|음성 명령| APP
    SG -->|"MJPEG over Wi-Fi"| APP
    APP -->|"HTTPS REST"| BE
    BE --> DB
    BE --> CACHE
    BE --> ROUTING
    BE -->|SMS 인증| FIREBASE
    ROUTING -->|지도 데이터| OSM
    APP -.->|VPS 위치| ARCORE
    APP -.->|STT / TTS| ANDROID_API
    APP -.->|GPS 위치| GPS_API

    OPERATOR -->|배포 · 운영| SERVER
```

---

## 2. 주요 시나리오 통합 시퀀스 다이어그램

> 시나리오 A · B · C 는 실제 운용 시 **동시에 병행** 수행됩니다.

```mermaid
sequenceDiagram
    actor 사용자 as 👤 시각장애인 사용자
    participant SG as 📷 스마트글래스
    participant APP as 📱 Android 앱
    participant BE as ⚙️ 백엔드 서버
    participant EXT as 🌐 외부 서비스

    %% ══════════════════════════════════════════
    Note over 사용자,EXT: 【시나리오 A】 음성 목적지 입력 & 경로 안내 (P1)
    %% ══════════════════════════════════════════

    rect rgb(220, 240, 255)
        사용자 ->> APP: 음성 명령 ("경복궁으로 안내해줘")
        APP ->> EXT: STT 음성 인식 요청
        EXT -->> APP: 인식 텍스트 반환
        APP ->> APP: 음성 명령 파싱, 목적지 추출
        APP ->> BE: POST /route (출발지, 목적지)
        BE ->> EXT: OSRM 경로 계산 요청
        EXT -->> BE: 턴-바이-턴 경로 반환
        BE -->> APP: 경로 + 안내 지시 응답
        APP ->> APP: 경로 안내 문장 생성
        APP ->> EXT: TTS 음성 출력
        EXT -->> 사용자: 🔊 "50미터 앞에서 좌회전입니다"
        loop 이동 중 안내 반복
            APP ->> EXT: TTS (다음 안내 지점마다)
            EXT -->> 사용자: 🔊 경로 안내 음성
        end
        APP ->> EXT: TTS 목적지 도착 안내
        EXT -->> 사용자: 🔊 "목적지에 도착했습니다"
    end

    %% ══════════════════════════════════════════
    Note over 사용자,EXT: 【시나리오 B】 실시간 장애물 감지 & 음성 경보 (P1, A와 병행)
    %% ══════════════════════════════════════════

    rect rgb(255, 235, 220)
        loop 500ms 주기 (이동 중 상시)
            SG ->> APP: MJPEG 프레임 스트리밍 (Wi-Fi)
            APP ->> APP: YOLOv8n On-device 추론
            APP ->> APP: 위험도 필터링, dangerLevel ≥ 0.5
            APP ->> APP: 클래스별 4초 쿨다운 적용
            alt 위험 객체 감지됨
                APP ->> APP: 객체 → 한국어 경보 문장 생성
                APP ->> EXT: TTS 경보 출력 (HIGH 우선순위)
                EXT -->> 사용자: 🔊 "앞에 자동차 조심하세요"
            end
        end
    end

    %% ══════════════════════════════════════════
    Note over 사용자,EXT: 【시나리오 C】 경로 이탈 감지 & 자동 재탐색 (P2, A와 병행)
    %% ══════════════════════════════════════════

    rect rgb(220, 255, 230)
        loop 위치 갱신 주기 (이동 중 상시)
            EXT ->> APP: GPS + ARCore VPS + IMU 센서 데이터
            APP ->> APP: 위치 융합 (칼만 필터)
            APP ->> BE: GET /nearest (도로 스냅 요청)
            BE -->> APP: 도로 위 보정 좌표 반환
            APP ->> APP: 경로 이탈 여부 판단 (기준 15m)
            alt 이탈 거리 > 15m
                APP ->> EXT: TTS 안내
                EXT -->> 사용자: 🔊 "경로를 재탐색합니다"
                APP ->> BE: POST /reroute (현재 위치, 목적지)
                BE ->> EXT: OSRM 새 경로 계산
                EXT -->> BE: 새 경로 반환
                BE -->> APP: 새 턴-바이-턴 경로 응답
                APP ->> EXT: TTS 새 경로 첫 안내
                EXT -->> 사용자: 🔊 새 경로 음성 안내
            end
        end
    end
```

---

## 3. 시나리오 요약표

| 시나리오 | 트리거 | 주요 흐름 | 출력 |
|---------|--------|-----------|------|
| **A. 경로 안내** | 사용자 음성 명령 | STT → 경로 계산 (OSRM) → 턴-바이-턴 | TTS 음성 안내 |
| **B. 장애물 경보** | 카메라 프레임 (상시) | MJPEG → YOLOv8n 추론 → 위험도 필터 | TTS 경보 (4초 쿨다운) |
| **C. 자동 재탐색** | 경로 이탈 15m 초과 | GPS 융합 → 이탈 감지 → 재탐색 요청 | TTS 재탐색 안내 |

> **B · C는 A(경로 안내) 진행 중 상시 병행 동작**

---

*파일 위치: `c:/capstoneDesign/service-flow.md`*
*spec 기준: `specs/001-smartglass-nav-system/spec.md` (2026-01-30)*
