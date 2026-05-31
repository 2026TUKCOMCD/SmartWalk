.PHONY: dev full setup down down-prod logs ps prod

# 개발: postgres + redis + OSRM 데모 프록시 (데이터 불필요)
dev:
	docker compose -f docker/docker-compose.yml --profile dev up -d

# 풀스택 개발: postgres + redis + 실제 OSRM + Nominatim + nginx
# 주의: docker-setup 먼저 실행 필요 (OSRM 데이터), Nominatim 최초 기동 ~10분 소요
full:
	docker compose -f docker/docker-compose.yml --profile full up -d

# OSRM 데이터 다운로드 + 전처리 (최초 1회, 약 20~40분)
setup:
	docker compose -f docker/docker-compose.yml --profile setup up osrm-download osrm-prepare

# 개발 환경 중지
down:
	docker compose -f docker/docker-compose.yml down

# 로그 스트림
logs:
	docker compose -f docker/docker-compose.yml logs -f

# 컨테이너 상태
ps:
	docker compose -f docker/docker-compose.yml ps

# ─── 프로덕션 ──────────────────────────────────────────────

# 프로덕션 기동 (OSRM + Nominatim 포함)
prod:
	docker compose -f docker/docker-compose.prod.yml up -d

# 프로덕션 중지
down-prod:
	docker compose -f docker/docker-compose.prod.yml down

# 프로덕션 로그
logs-prod:
	docker compose -f docker/docker-compose.prod.yml logs -f

# 프로덕션 상태
ps-prod:
	docker compose -f docker/docker-compose.prod.yml ps
