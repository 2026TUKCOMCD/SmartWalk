# 백엔드 개발 가이드

## 환경 설정

### 사전 요구사항

- JDK 21
- Docker Desktop (PostgreSQL, Redis, OSRM, Nominatim 실행용)
- Firebase Admin SDK 키 파일 (`setup.md` 참고)

### Firebase 키 배치

Firebase 콘솔 → 프로젝트 설정 → 서비스 계정 → JSON 키 생성 후:

```
backend/smartwalker-firebase-adminsdk-key.json
```

이 파일은 `.gitignore`에 등록되어 있어 커밋되지 않습니다.

개발 중 Firebase 인증을 건너뛰려면:

```bash
# backend 실행 시 환경변수 추가
FIREBASE_DISABLED=true ./mvnw spring-boot:run
# 이후 X-User-Id 헤더로 사용자 ID를 직접 전달
```

---

## 실행

```bash
# Docker 서비스 먼저 시작
npm run docker:up

# 백엔드 실행 (localhost:8080)
npm run backend:dev
```

또는 backend 디렉토리에서 직접:

```bash
cd backend
./mvnw spring-boot:run --args='--spring.profiles.active=local'
```

---

## 빌드 및 테스트

```bash
npm run backend:build   # JAR 빌드 (테스트 스킵)
npm run backend:test    # 전체 테스트 실행
```

또는:

```bash
cd backend
./mvnw package -DskipTests   # JAR: target/navblind-server-*.jar
./mvnw test                  # JUnit 5 + TestContainers
```

---

## 프로젝트 구조

```
backend/src/main/java/com/navblind/server/
├── config/         # Spring 설정 (Firebase, Redis, OSRM 등)
├── controller/     # REST 컨트롤러
├── service/        # 비즈니스 로직
├── repository/     # JPA 리포지토리
├── entity/         # JPA 엔티티
├── dto/            # Request/Response DTO
└── integration/    # OSRM, Nominatim 클라이언트
```

---

## 주요 API

Base URL: `http://localhost:8080/v1`

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/navigation/route` | 경로 계산 |
| POST | `/navigation/reroute` | 경로 재계산 |
| PATCH | `/navigation/sessions/{id}` | 세션 상태 업데이트 |
| GET | `/navigation/sessions` | 이동 기록 조회 |
| GET | `/navigation/nearest` | 좌표 → 가장 가까운 도로 snap |
| GET | `/destinations/search` | POI 검색 (Nominatim) |
| GET | `/destinations` | 저장된 목적지 조회 |
| POST | `/auth/verify` | Firebase 토큰 검증 |

전체 스펙: `specs/001-smartglass-nav-system/contracts/api.yaml`

---

## 환경 변수

`application.yml`의 기본값 기준:

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DB_HOST` | `localhost` | PostgreSQL 호스트 |
| `DB_PORT` | `5432` | PostgreSQL 포트 |
| `DB_NAME` | `navblind` | 데이터베이스 이름 |
| `DB_USERNAME` | `navblind` | DB 사용자 |
| `DB_PASSWORD` | `navblind_dev` | DB 비밀번호 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `OSRM_BASE_URL` | `http://localhost:5000` | OSRM 서버 |
| `NOMINATIM_BASE_URL` | `http://localhost:8088` | Nominatim 서버 |
| `FIREBASE_CREDENTIALS_PATH` | `smartwalker-firebase-adminsdk-key.json` | 키 파일 경로 |
| `FIREBASE_DISABLED` | `false` | `true`면 Firebase 인증 건너뜀 |

---

## 경로 계산 (OSRM)

OSRM은 Docker로 로컬에서 실행합니다. 한국 OSM 데이터 최초 다운로드 및 전처리가 필요합니다 (`deployment.md` 3번 참고).

경로 결과는 Redis에 10분간 캐시됩니다 (`RouteCacheService`).

재탐색(`/reroute`)은 출발점이 달라지므로 캐시를 사용하지 않고 OSRM에 직접 요청합니다.

---

## 데이터베이스

- Flyway 자동 마이그레이션 (`resources/db/migration/`)
- 개발 중: `ddl-auto: update` (스키마 자동 반영)
- 운영: `ddl-auto: validate` + Flyway 마이그레이션

전체 스키마: `specs/001-smartglass-nav-system/data-model.md`

---

## 테스트

단위 테스트: JUnit 5, Mockito

통합 테스트: TestContainers (실제 PostgreSQL, Redis 컨테이너 사용)

```bash
cd backend
./mvnw test
```

TestContainers는 Docker가 실행 중이어야 합니다.
