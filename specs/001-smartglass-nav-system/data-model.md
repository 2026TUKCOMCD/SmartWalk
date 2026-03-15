# Data Model: Smart Glass Navigation System

**Date**: 2026-01-30
**Feature**: 001-smartglass-nav-system

## Entity Relationship Diagram

```
┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
│      User       │       │   Destination   │       │   Preference    │
├─────────────────┤       ├─────────────────┤       ├─────────────────┤
│ id (PK)         │──┐    │ id (PK)         │       │ id (PK)         │
│ phone_number    │  │    │ user_id (FK)    │◄──┐   │ user_id (FK)    │◄──┐
│ created_at      │  │    │ name            │   │   │ key             │   │
│ last_login      │  │    │ latitude        │   │   │ value           │   │
│ is_active       │  │    │ longitude       │   │   │ updated_at      │   │
└─────────────────┘  │    │ address         │   │   └─────────────────┘   │
                     │    │ label           │   │                         │
                     │    │ use_count       │   │                         │
                     │    │ created_at      │   │                         │
                     │    └─────────────────┘   │                         │
                     │                          │                         │
                     └──────────────────────────┴─────────────────────────┘
                                    1:N relationships

┌─────────────────┐       ┌─────────────────┐
│NavigationSession│       │  SmartGlasses   │
├─────────────────┤       ├─────────────────┤
│ id (PK)         │       │ id (PK)         │
│ user_id (FK)    │◄──────│ user_id (FK)    │
│ origin_lat      │       │ device_id       │
│ origin_lng      │       │ mac_address     │
│ dest_lat        │       │ name            │
│ dest_lng        │       │ last_connected  │
│ dest_name       │       │ created_at      │
│ status          │       └─────────────────┘
│ started_at      │
│ completed_at    │
│ distance_meters │
└─────────────────┘
```

## Entities

### User

사용자 계정 정보를 저장합니다.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | 고유 식별자 |
| phone_number | VARCHAR(20) | UNIQUE, NOT NULL | 전화번호 (인증용) |
| display_name | VARCHAR(100) | NULL | 사용자 표시 이름 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 계정 생성 시간 |
| last_login | TIMESTAMP | NULL | 마지막 로그인 시간 |
| is_active | BOOLEAN | NOT NULL, DEFAULT TRUE | 계정 활성화 상태 |

**Validation Rules**:
- phone_number: 한국 전화번호 형식 (010-XXXX-XXXX)
- display_name: 최대 100자

---

### Destination

사용자가 저장한 자주 가는 목적지입니다.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | 고유 식별자 |
| user_id | UUID | FK → User.id, NOT NULL | 소유 사용자 |
| name | VARCHAR(200) | NOT NULL | 목적지 이름 |
| latitude | DECIMAL(10,8) | NOT NULL | 위도 |
| longitude | DECIMAL(11,8) | NOT NULL | 경도 |
| address | VARCHAR(500) | NULL | 도로명 주소 |
| label | VARCHAR(50) | NULL | 레이블 (집, 회사 등) |
| use_count | INTEGER | NOT NULL, DEFAULT 0 | 사용 횟수 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 생성 시간 |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 수정 시간 |

**Validation Rules**:
- latitude: -90 ~ 90 범위
- longitude: -180 ~ 180 범위
- label: 미리 정의된 값 또는 사용자 정의 (집, 회사, 병원, 기타)

**Indexes**:
- `idx_destination_user_id` on (user_id)
- `idx_destination_use_count` on (user_id, use_count DESC)

---

### Preference

사용자 접근성 및 경로 설정입니다.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | 고유 식별자 |
| user_id | UUID | FK → User.id, NOT NULL | 소유 사용자 |
| key | VARCHAR(100) | NOT NULL | 설정 키 |
| value | VARCHAR(500) | NOT NULL | 설정 값 |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 수정 시간 |

**Preference Keys**:
| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `avoid_stairs` | boolean | false | 계단 회피 |
| `prefer_crosswalk_signals` | boolean | true | 신호등 있는 횡단보도 선호 |
| `voice_speed` | enum | normal | 음성 속도 (slow/normal/fast) |
| `alert_distance` | integer | 5 | 장애물 경고 거리 (미터) |
| `vibration_enabled` | boolean | true | 진동 알림 사용 |

**Constraints**:
- UNIQUE(user_id, key) - 사용자당 키 중복 불가

---

### NavigationSession

활성 또는 완료된 네비게이션 세션입니다.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | 고유 식별자 |
| user_id | UUID | FK → User.id, NOT NULL | 사용자 |
| origin_lat | DECIMAL(10,8) | NOT NULL | 출발지 위도 |
| origin_lng | DECIMAL(11,8) | NOT NULL | 출발지 경도 |
| dest_lat | DECIMAL(10,8) | NOT NULL | 목적지 위도 |
| dest_lng | DECIMAL(11,8) | NOT NULL | 목적지 경도 |
| dest_name | VARCHAR(200) | NOT NULL | 목적지 이름 |
| status | ENUM | NOT NULL | 세션 상태 |
| started_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 시작 시간 |
| completed_at | TIMESTAMP | NULL | 완료 시간 |
| distance_meters | INTEGER | NULL | 총 거리 (미터) |
| reroute_count | INTEGER | NOT NULL, DEFAULT 0 | 재탐색 횟수 |

**Status Enum**:
- `ACTIVE` - 진행 중
- `COMPLETED` - 정상 완료
- `CANCELLED` - 사용자 취소
- `FAILED` - 실패 (경로 없음 등)

**Indexes**:
- `idx_session_user_active` on (user_id, status) WHERE status = 'ACTIVE'
- `idx_session_user_history` on (user_id, started_at DESC)

---

### SmartGlasses

등록된 스마트글래스 디바이스입니다.

| Field | Type | Constraints | Description |
|-------|------|-------------|-------------|
| id | UUID | PK | 고유 식별자 |
| user_id | UUID | FK → User.id, NOT NULL | 소유 사용자 |
| device_id | VARCHAR(100) | UNIQUE, NOT NULL | 디바이스 고유 ID |
| mac_address | VARCHAR(17) | NULL | MAC 주소 |
| name | VARCHAR(100) | NOT NULL, DEFAULT 'Smart Glasses' | 디바이스 이름 |
| last_connected | TIMESTAMP | NULL | 마지막 연결 시간 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW() | 등록 시간 |

**Validation Rules**:
- mac_address: XX:XX:XX:XX:XX:XX 형식
- 사용자당 최대 3개 디바이스 등록 가능

---

## State Transitions

### NavigationSession Status

```
┌─────────┐    start     ┌────────┐
│  (new)  │─────────────►│ ACTIVE │
└─────────┘              └────┬───┘
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
        ┌───────────┐  ┌───────────┐  ┌────────┐
        │ COMPLETED │  │ CANCELLED │  │ FAILED │
        └───────────┘  └───────────┘  └────────┘
         (arrived)      (user stop)    (no route)
```

---

## Redis Cache Schema

### Session Cache
```
Key: session:{userId}
TTL: 24 hours
Value: {
  "userId": "uuid",
  "token": "jwt-token",
  "deviceId": "device-uuid",
  "createdAt": "timestamp"
}
```

### User Preferences Cache
```
Key: user:{userId}:prefs
TTL: 7 days
Value: {
  "avoidStairs": true,
  "preferCrosswalkSignals": true,
  "voiceSpeed": "normal",
  "alertDistance": 5
}
```

### Route Cache
```
Key: route:{md5(origin+dest+prefs)}
TTL: 1 hour
Value: {
  "waypoints": [...],
  "instructions": [...],
  "distance": 1234,
  "duration": 900
}
```

### Active Navigation
```
Key: nav:{userId}:active
TTL: 4 hours (auto-expire abandoned sessions)
Value: {
  "sessionId": "uuid",
  "currentStep": 3,
  "lastPosition": {"lat": 37.5, "lng": 127.0},
  "updatedAt": "timestamp"
}
```

---

## Android Navigation Domain Models

서버에서 받은 경로 데이터를 앱 내에서 표현하는 도메인 모델입니다.

### Route

| Field | Type | Description |
|-------|------|-------------|
| sessionId | UUID | 서버 NavigationSession ID |
| distance | Int | 전체 경로 거리 (미터) |
| duration | Int | 예상 소요 시간 (초) |
| waypoints | List\<Waypoint\> | 경유 좌표 목록 |
| instructions | List\<Instruction\> | 턴-바이-턴 안내 목록 |

### Instruction

| Field | Type | Description |
|-------|------|-------------|
| step | Int | 안내 순서 인덱스 |
| type | InstructionType | 안내 종류 |
| modifier | TurnModifier? | 회전 방향 (해당 시) |
| text | String | OSRM 원본 안내 텍스트 |
| distance | Int | 다음 안내까지 거리 (미터) |
| location | Waypoint | 안내 시작 좌표 |

### InstructionType

| Value | Description |
|-------|-------------|
| `DEPART` | 출발 안내 |
| `TURN` | 회전 안내 (modifier로 방향 지정) |
| `CONTINUE` | 직진 유지 |
| `CROSSWALK` | 횡단보도 횡단 안내 — 시각장애인 안전을 위해 HIGH 우선순위로 처리 |
| `ARRIVE` | 목적지 도착 |

### TurnModifier

| Value | Description |
|-------|-------------|
| `LEFT` | 좌회전 |
| `RIGHT` | 우회전 |
| `STRAIGHT` | 직진 |
| `SLIGHT_LEFT` | 약간 좌측 |
| `SLIGHT_RIGHT` | 약간 우측 |
| `UTURN` | 유턴 |

### FusedPosition

앱 내에서 여러 위치 소스를 융합한 현재 위치 정보입니다.

| Field | Type | Description |
|-------|------|-------------|
| coordinate | Coordinate | 위경도 |
| accuracy | Float | 정확도 반경 (미터) |
| heading | Float? | GPS 이동 방향 (절대 방위각) |
| fusedHeading | Float? | IMU+VPS 융합 방위각 (사용자가 바라보는 방향) |
| headingConfidence | Float? | fusedHeading 신뢰도 (0.0~1.0) |
| source | PositionSource | 위치 출처 |
| isMoving | Boolean | 이동 중 여부 |

**정확도 기준** (SC-003):
- `isHighAccuracy`: accuracy < **5m** — 고정밀 (ARCore Geospatial 활성 시)
- `isAcceptable`: accuracy < 20m — 사용 가능 (GPS 단독)

**PositionSource**:
- `GPS` — 기본 GPS
- `FUSED_LOCATION` — Android FusedLocationProvider
- `ARCORE_GEOSPATIAL` — ARCore VPS
- `NETWORK` — Wi-Fi/셀룰러
- `UNKNOWN`

---

## Android Local Storage (Room Database)

### LocalDestination
캐시된 목적지 (오프라인 사용).

```kotlin
@Entity(tableName = "destinations")
data class LocalDestination(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String?,
    val label: String?,
    val useCount: Int,
    val syncedAt: Long
)
```

### LocalPreference
캐시된 설정.

```kotlin
@Entity(tableName = "preferences")
data class LocalPreference(
    @PrimaryKey val key: String,
    val value: String,
    val syncedAt: Long
)
```

### CachedRoute
오프라인 경로 캐시.

```kotlin
@Entity(tableName = "cached_routes")
data class CachedRoute(
    @PrimaryKey val id: String,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val routeJson: String,  // Serialized route data
    val createdAt: Long,
    val expiresAt: Long
)
```

---

## Data Validation Summary

| Entity | Field | Rule |
|--------|-------|------|
| User | phone_number | 한국 전화번호 정규식: `^01[0-9]-?[0-9]{4}-?[0-9]{4}$` |
| Destination | latitude | -90.0 ≤ value ≤ 90.0 |
| Destination | longitude | -180.0 ≤ value ≤ 180.0 |
| Preference | voice_speed | IN ('slow', 'normal', 'fast') |
| Preference | alert_distance | 1 ≤ value ≤ 20 |
| SmartGlasses | mac_address | 정규식: `^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$` |
