# NavBlind 배포 가이드

## 사전 요구사항

| 항목 | 버전 |
|------|------|
| Docker | 24+ |
| Docker Compose | 2.20+ |
| 서버 OS | Ubuntu 22.04 LTS |
| RAM | 최소 4 GB (OSRM + Nominatim 포함 시 8 GB) |
| 디스크 | 최소 20 GB (한국 OSM 데이터 포함 시 50 GB) |

---

## 1. 환경 변수 설정

```bash
cp docker/.env.example docker/.env
# .env 파일을 편집하여 실제 값 입력
```

필수 환경 변수:

```dotenv
DOMAIN=navblind.example.com
POSTGRES_USER=navblind
POSTGRES_PASSWORD=<strong-password>
REDIS_PASSWORD=<strong-password>
NOMINATIM_PASSWORD=<strong-password>
APP_VERSION=1.0.0
```

---

## 2. SSL 인증서 발급 (Let's Encrypt)

처음 배포 시 인증서가 없으므로 HTTP 모드로 먼저 certbot을 실행합니다.

```bash
# 1. nginx를 HTTP 전용으로 임시 실행
docker compose -f docker/docker-compose.prod.yml up -d nginx

# 2. 인증서 발급
docker run --rm \
  -v $(pwd)/docker/nginx/ssl:/etc/letsencrypt \
  -v $(pwd)/docker/nginx/certbot-webroot:/var/www/certbot \
  certbot/certbot certonly \
  --webroot --webroot-path=/var/www/certbot \
  -d ${DOMAIN} --email admin@${DOMAIN} --agree-tos --non-interactive

# 3. nginx-ssl.conf의 ${DOMAIN} 치환 후 재시작
sed -i "s/\${DOMAIN}/${DOMAIN}/g" docker/nginx/nginx-ssl.conf
docker compose -f docker/docker-compose.prod.yml restart nginx
```

---

## 3. OSRM 데이터 준비 (최초 1회)

한국 OSM 데이터 다운로드 및 전처리 (약 20~40분 소요):

```bash
cd docker
docker compose -f docker-compose.prod.yml --profile setup up osrm-download osrm-prepare
```

---

## 4. 전체 서비스 시작

```bash
cd docker
docker compose -f docker-compose.prod.yml up -d
```

서비스 상태 확인:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

---

## 5. 백엔드 빌드 및 이미지 배포

```bash
cd backend
./gradlew build -x test

docker build -t navblind-backend:${APP_VERSION} .
# 또는 CI/CD에서 자동 빌드 후 레지스트리 push
docker tag navblind-backend:${APP_VERSION} ghcr.io/<org>/navblind-backend:${APP_VERSION}
docker push ghcr.io/<org>/navblind-backend:${APP_VERSION}
```

프로덕션 서버에서 새 버전 배포:

```bash
APP_VERSION=1.1.0 docker compose -f docker/docker-compose.prod.yml up -d --no-deps backend
```

---

## 6. Android APK 배포

```bash
cd android
./gradlew assembleRelease

# Release APK: android/app/build/outputs/apk/release/app-release.apk
# Firebase App Distribution 또는 Google Play로 배포
```

빌드 시 필요한 환경 변수:

```bash
# android/local.properties
API_BASE_URL=https://navblind.example.com
USE_LOCAL_CAMERA=false
```

---

## 7. ESP32-CAM 펌웨어 플래시

```bash
cd smartglass

# WiFi 자격증명을 빌드 플래그로 전달
# platformio.ini의 build_flags에 추가:
# -DWIFI_SSID=\"MyNetwork\" -DWIFI_PASS=\"MyPassword\"

pio run --target upload
pio device monitor
```

플래시 후 시리얼 모니터에서 확인:
```
[NavBlind] Device ID: NAVBLIND-AABBCC
[NavBlind] Ready. http://192.168.1.x/stream
```

Android 앱에서 "기기 추가" → IP 주소 입력 후 페어링합니다.

---

## 8. 헬스체크 엔드포인트

| 서비스 | URL |
|--------|-----|
| 백엔드 | `GET /actuator/health` |
| Nginx | `GET /health` |
| Nominatim | `GET http://nominatim:8080/status` |

---

## 9. 로그 조회

```bash
# 백엔드 최근 100줄
docker compose -f docker/docker-compose.prod.yml logs --tail=100 backend

# 실시간 스트림
docker compose -f docker/docker-compose.prod.yml logs -f nginx backend
```

프로덕션 로그는 Logstash JSON 형식으로 출력됩니다 (`logback-spring.xml` 참조).

---

## 10. 백업

```bash
# PostgreSQL 덤프
docker exec navblind-postgres pg_dump -U navblind navblind \
  | gzip > backup_$(date +%Y%m%d).sql.gz

# Redis RDB 복사
docker cp navblind-redis:/data/dump.rdb ./redis-backup-$(date +%Y%m%d).rdb
```

---

## 문제 해결

### 백엔드가 DB에 연결 못 할 때
```bash
docker compose -f docker/docker-compose.prod.yml logs postgres
# postgres가 healthy 상태인지 확인
docker compose -f docker/docker-compose.prod.yml ps postgres
```

### 인증서 갱신 실패 시
```bash
docker compose -f docker/docker-compose.prod.yml restart certbot
docker compose -f docker/docker-compose.prod.yml logs certbot
```

### ESP32 Wi-Fi 연결 실패 시
- STA 모드 실패 → 자동으로 AP 모드 전환
- AP SSID: `NavBlind-XXYYZZ` (X,Y,Z = MAC 마지막 3바이트)
- AP Password: `navblind1`
- Android에서 위 AP에 연결 후 `192.168.4.1` 로 접근
