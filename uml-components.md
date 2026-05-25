# UML 컴포넌트 다이어그램 — NavBlind 시스템

> Mermaid `graph LR/TB` + subgraph 로 UML Component Diagram 근사 표현.
> 렌더링 확인: [mermaid.live](https://mermaid.live)

---

## 다이어그램 1 — Android 앱 컴포넌트 다이어그램

```mermaid
graph TB
    subgraph Android["Android 앱"]
        direction TB

        subgraph UI["UI / ViewModel 계층"]
            NavUI["NavigationActivity<br>/ ViewModel"]
        end

        subgraph SVC["서비스 계층"]
            Voice["음성 서비스<br><<provides>> TTS 출력<br><<requires>> STT 입력"]
            Detection["객체 검출<br><<provides>> ObjectDetectionResult"]
            Location["위치 서비스<br><<provides>> FusedPosition"]
            Streaming["스트리밍 수신<br><<provides>> CameraFrame"]
        end

        subgraph DATA["데이터 계층"]
            Repo["Repository<br><<provides>> REST API<br><<provides>> LocalCache"]
        end
    end

    subgraph EXT_HW["외부 하드웨어"]
        ESP32["ESP32-CAM<br>(Smart Glasses)"]
    end

    subgraph EXT_OS["외부 OS API"]
        ARCore["ARCore / GPS"]
        STTTTS["STT / TTS<br>(Android OS)"]
    end

    subgraph Backend["백엔드 서버"]
        Server["Spring Boot API"]
    end

    NavUI -->|"observes<br>StateFlow"| Voice
    NavUI -->|"observes<br>StateFlow"| Detection
    NavUI -->|"observes<br>StateFlow"| Location

    Voice -->|"uses<br>ObjectDetectionResult"| Detection
    Voice -->|"uses<br>FusedPosition"| Location
    Voice -.->|"calls OS API"| STTTTS

    Detection -->|"uses<br>CameraFrame"| Streaming
    Location -.->|"uses OS API"| ARCore

    Streaming -->|"MJPEG / HTTP"| ESP32

    Voice -->|"calls<br>REST API"| Repo
    Location -->|"calls<br>REST API"| Repo
    Detection -->|"calls<br>REST API"| Repo

    Repo -->|"HTTPS REST"| Server
```

---

## 다이어그램 2 — 백엔드 레이어드 아키텍처 컴포넌트 다이어그램

```mermaid
graph TB
    subgraph Backend["Spring Boot 백엔드"]
        direction TB

        subgraph Controller["Controller 계층<br>(REST API 진입점)"]
            NavCtrl["NavigationController<br><<provides>> /v1/navigation/*"]
            AuthCtrl["AuthController<br><<provides>> /v1/auth/*"]
            DestCtrl["DestinationController<br><<provides>> /v1/destinations/*"]
        end

        subgraph Service["Service 계층<br>(비즈니스 로직)"]
            NavSvc["NavigationService<br>경로 탐색 · 재탐색"]
            UserSvc["UserService<br>인증 · 사용자 관리"]
            DestSvc["DestinationService<br>POI 검색 · 저장"]
        end

        subgraph Repository["Repository 계층<br>(데이터 접근)"]
            UserRepo["UserRepository<br>(JPA)"]
            DestRepo["DestinationRepository<br>(JPA)"]
            SessionRepo["NavigationSessionRepository<br>(JPA)"]
            OsrmClient["OsrmClient<br><<requires>> OSRM HTTP API"]
            NominatimClient["NominatimClient<br><<requires>> Nominatim HTTP API"]
            RedisCache["RedisCache<br><<requires>> Redis"]
        end

        subgraph Infra["인프라 계층"]
            PG["PostgreSQL 16<br><<provides>> JDBC"]
            Redis["Redis 7<br><<provides>> Cache"]
        end
    end

    subgraph ExtSvc["외부 서비스 (self-hosted)"]
        OSRM["OSRM Engine<br>보행자 경로 탐색"]
        Nominatim["Nominatim<br>Geocoding / POI 검색"]
    end

    subgraph ExtCloud["외부 클라우드"]
        Firebase["Firebase Auth<br>토큰 검증"]
    end

    NavCtrl -->|"calls"| NavSvc
    AuthCtrl -->|"calls"| UserSvc
    DestCtrl -->|"calls"| DestSvc

    NavSvc -->|"uses"| SessionRepo
    NavSvc -->|"uses"| OsrmClient
    NavSvc -->|"uses"| RedisCache
    UserSvc -->|"uses"| UserRepo
    DestSvc -->|"uses"| DestRepo
    DestSvc -->|"uses"| NominatimClient

    UserRepo -->|"JDBC"| PG
    DestRepo -->|"JDBC"| PG
    SessionRepo -->|"JDBC"| PG
    RedisCache -->|"Redis Protocol"| Redis

    OsrmClient -.->|"HTTP"| OSRM
    NominatimClient -.->|"HTTP"| Nominatim
    AuthCtrl -.->|"HTTPS<br>token verify"| Firebase
```

---

## 다이어그램 3 — 전체 시스템 컴포넌트 의존 관계도

```mermaid
graph LR
    subgraph HW["하드웨어"]
        ESP32["ESP32-CAM<br>Firmware (C++)"]
    end

    subgraph Mobile["Android 앱 (Kotlin)"]
        direction TB
        StreamRx["스트리밍 수신기<br>(MJPEG Receiver)"]
        YOLODet["객체 검출<br>(YOLOv8 TFLite)"]
        LocSvc["위치 서비스<br>(GPS + ARCore)"]
        VoiceSvc["음성 서비스<br>(TTS / STT)"]
        NavVM["Navigation ViewModel"]
        DataLayer["데이터 레이어<br>(Retrofit + Room)"]
    end

    subgraph OSApi["Android OS API"]
        ARCoreAPI["ARCore<br>(AR 위치 보정)"]
        GPSAPI["GPS<br>(위치)"]
        TTSAPI["TTS / STT<br>(음성 I/O)"]
    end

    subgraph ServerLayer["백엔드 서버 (Spring Boot / Java 21)"]
        direction TB
        APIGateway["Nginx<br>(Reverse Proxy)"]
        SpringApp["Spring Boot App"]
        PGDb["PostgreSQL 16"]
        RedisDb["Redis 7"]
    end

    subgraph SelfHosted["Self-hosted 서비스"]
        OSRMSvc["OSRM<br>(보행자 경로)"]
        NominatimSvc["Nominatim<br>(Geocoding)"]
    end

    subgraph CloudSvc["외부 클라우드 서비스"]
        FirebaseAuth["Firebase Auth"]
    end

    %% 하드웨어 → 모바일
    ESP32 -->|"MJPEG / HTTP<br>(WiFi)"| StreamRx

    %% 모바일 내부 의존
    StreamRx -->|"CameraFrame"| YOLODet
    YOLODet -->|"ObjectDetectionResult"| VoiceSvc
    LocSvc -->|"FusedPosition"| VoiceSvc
    LocSvc -->|"FusedPosition"| NavVM
    VoiceSvc -->|"NavigationInstruction"| NavVM
    NavVM -->|"calls"| DataLayer

    %% 모바일 → OS API
    LocSvc -.->|"Location API"| ARCoreAPI
    LocSvc -.->|"Location API"| GPSAPI
    VoiceSvc -.->|"TTS / STT API"| TTSAPI

    %% 모바일 → 백엔드
    DataLayer -->|"HTTPS REST<br>(Retrofit)"| APIGateway
    APIGateway -->|"HTTP"| SpringApp

    %% 백엔드 내부
    SpringApp -->|"JDBC"| PGDb
    SpringApp -->|"Redis Protocol"| RedisDb

    %% 백엔드 → Self-hosted
    SpringApp -.->|"HTTP<br>(route API)"| OSRMSvc
    SpringApp -.->|"HTTP<br>(geocoding)"| NominatimSvc

    %% 백엔드 → 클라우드
    SpringApp -.->|"HTTPS<br>(token verify)"| FirebaseAuth
```

---

## 인터페이스 요약표

| 컴포넌트 | 제공 인터페이스 (`<<provides>>`) | 요구 인터페이스 (`<<requires>>`) |
|---|---|---|
| ESP32-CAM | MJPEG HTTP 스트림 | WiFi 네트워크 |
| 스트리밍 수신기 | CameraFrame (Bitmap) | MJPEG HTTP 엔드포인트 |
| 객체 검출 | ObjectDetectionResult | CameraFrame |
| 위치 서비스 | FusedPosition | ARCore API, GPS API |
| 음성 서비스 | TTS 출력, NavigationInstruction | ObjectDetectionResult, FusedPosition, TTS/STT OS API |
| 데이터 레이어 | LocalCache (Room), REST 래퍼 | HTTPS REST API |
| NavigationController | `/v1/navigation/*` REST | NavigationService |
| NavigationService | 경로 계산 결과 | OsrmClient, SessionRepository, Redis |
| OsrmClient | Route 객체 | OSRM HTTP API |
| NominatimClient | POI 검색 결과 | Nominatim HTTP API |
| PostgreSQL | JDBC | 디스크 스토리지 |
| Redis | Cache API | 메모리 |
