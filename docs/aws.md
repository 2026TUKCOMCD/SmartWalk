# AWS 배포 가이드 (EC2 + Docker Compose)

## 아키텍처 요약

단일 EC2 인스턴스에 기존 `docker-compose.prod.yml`을 그대로 올립니다.
OSRM은 관리형 서비스가 없으므로 자체 호스팅이 필수입니다(장소 검색/지오코딩은 Kakao Local API를 REST로 호출하므로 별도 인프라 불필요).

```
인터넷
  │
  ▼ 443/80
[Elastic IP] → [EC2 t3.large]
                 ├── nginx (리버스 프록시 + SSL)
                 ├── backend (Spring Boot)
                 ├── postgres
                 ├── redis
                 └── osrm
                 │
                [EBS 60 GB gp3]
```

---

## 비용 (ap-northeast-2 서울, 2025년 기준)

| 항목 | On-Demand | 1년 Savings Plan | 비고 |
|------|-----------|-----------------|------|
| t3.large (8 GB) | ~$60/월 | **~$38/월** | 백엔드+DB+OSRM |
| EBS gp3 60 GB | ~$5/월 | $5/월 | 데이터 볼륨 |
| Elastic IP | $0 | $0 | 인스턴스에 연결 시 무료 |
| 데이터 전송 | ~$1/월 | ~$1/월 | 100 GB 이하 추정 |
| **합계** | **~$66/월** | **~$44/월** | |

> **핵심 절감**: EC2 Instance Savings Plan 1년(선결제 없음)으로 온디맨드 대비 약 37% 절감.
> 발표/시연 기간에만 켜는 경우 인스턴스 중지 시 EC2 요금은 $0 (EBS $5만 발생).

---

## 1. EC2 인스턴스 생성

AWS 콘솔 → EC2 → Launch Instance:

| 항목 | 값 |
|------|-----|
| AMI | Ubuntu Server 22.04 LTS (x86_64) |
| Instance type | `t3.large` |
| Key pair | 새로 생성 후 `.pem` 보관 |
| Storage | 60 GB, gp3 |
| Security Group | 아래 표 참고 |

### Security Group 인바운드 규칙

| 포트 | 프로토콜 | 소스 | 용도 |
|------|----------|------|------|
| 22 | TCP | 내 IP만 | SSH |
| 80 | TCP | 0.0.0.0/0 | HTTP (certbot 인증용) |
| 443 | TCP | 0.0.0.0/0 | HTTPS |

> OSRM(5000), PostgreSQL(5432) 포트는 외부에 열지 않습니다. 컨테이너 간 내부 네트워크로만 통신합니다.

---

## 2. Elastic IP 연결

```bash
# AWS 콘솔 → EC2 → Elastic IPs → Allocate → Associate
# 또는 CLI:
aws ec2 allocate-address --domain vpc
aws ec2 associate-address --instance-id i-xxxx --allocation-id eipalloc-xxxx
```

도메인이 있다면 DNS A 레코드를 Elastic IP로 지정합니다.

---

## 3. 서버 초기 설정

```bash
ssh -i ~/.ssh/your-key.pem ubuntu@<ELASTIC_IP>

# Docker 설치
sudo apt update && sudo apt upgrade -y
sudo apt install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
  https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker ubuntu
newgrp docker

# OSRM 데이터 전처리(osrm-extract 등, 한국 전역) 시 메모리 여유 확보용 스왑
sudo fallocate -l 8G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

---

## 4. 코드 배포

```bash
git clone https://github.com/<org>/smartwalker.git
cd smartwalker

# 환경 변수 설정
cp docker/.env.example docker/.env
nano docker/.env
```

`.env` 필수 값:

```dotenv
DOMAIN=api.yourdomain.com
POSTGRES_USER=navblind
POSTGRES_PASSWORD=<strong-password>
REDIS_PASSWORD=<strong-password>
APP_VERSION=1.0.0
```

---

## 5. OSRM 데이터 준비 (최초 1회, 약 20~40분)

```bash
cd docker
docker compose -f docker-compose.prod.yml --profile setup up osrm-download osrm-prepare
```

---

## 6. SSL 인증서 발급

도메인이 Elastic IP를 가리키고 있어야 합니다.

```bash
# nginx HTTP 전용으로 먼저 기동
docker compose -f docker-compose.prod.yml up -d nginx

# certbot으로 인증서 발급
docker run --rm \
  -v $(pwd)/nginx/ssl:/etc/letsencrypt \
  -v $(pwd)/nginx/certbot-webroot:/var/www/certbot \
  certbot/certbot certonly \
  --webroot --webroot-path=/var/www/certbot \
  -d ${DOMAIN} --email admin@${DOMAIN} --agree-tos --non-interactive

# nginx-ssl.conf에 도메인 적용 후 재시작
sed -i "s/\${DOMAIN}/${DOMAIN}/g" nginx/nginx-ssl.conf
docker compose -f docker-compose.prod.yml restart nginx
```

---

## 7. 전체 서비스 기동

```bash
cd docker
docker compose -f docker-compose.prod.yml up -d

# 상태 확인
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f backend
```

백엔드 헬스체크: `curl https://<DOMAIN>/actuator/health`

---

## 8. 배포 자동화 (GitHub Actions)

`.github/workflows/deploy.yml`:

```yaml
name: Deploy to EC2

on:
  push:
    branches: [main]
    paths: [backend/**]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Build JAR
        run: cd backend && ./mvnw package -DskipTests

      - name: Build & push Docker image
        run: |
          echo ${{ secrets.GHCR_TOKEN }} | docker login ghcr.io -u ${{ github.actor }} --password-stdin
          docker build -t ghcr.io/${{ github.repository_owner }}/smartwalker-backend:${{ github.sha }} backend/
          docker push ghcr.io/${{ github.repository_owner }}/smartwalker-backend:${{ github.sha }}

      - name: Deploy on EC2
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ubuntu
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            cd ~/smartwalker
            git pull
            APP_VERSION=${{ github.sha }} \
              docker compose -f docker/docker-compose.prod.yml up -d --no-deps backend
```

GitHub Secrets에 추가:
- `EC2_HOST`: Elastic IP 또는 도메인
- `EC2_SSH_KEY`: `.pem` 파일 내용 (전체)
- `GHCR_TOKEN`: GitHub Personal Access Token (`write:packages` 권한)

---

## 9. 비용 절감 팁

### Savings Plan 적용 (항상 켜두는 경우)

AWS 콘솔 → Cost Management → Savings Plans → Purchase

- **EC2 Instance Savings Plan** 선택
- 인스턴스 패밀리: `t3`, 리전: `ap-northeast-2`
- 기간: 1년, 선결제: 없음 → **약 $38/월**

### 시연 기간에만 켜기 (가장 저렴)

인스턴스를 사용하지 않을 때 중지하면 EC2 요금 $0 (EBS $5/월만 발생).

```bash
# 중지 (데이터 보존, EC2 과금 중단)
aws ec2 stop-instances --instance-ids i-xxxx

# 재시작
aws ec2 start-instances --instance-ids i-xxxx

# 재시작 후 서비스 자동 기동 (재부팅 시 자동 실행 등록)
```

재시작 시 Docker 서비스 자동 기동 설정:

```bash
# EC2 서버에서 1회 실행
sudo systemctl enable docker

# crontab으로 재부팅 시 compose 자동 기동
(crontab -l 2>/dev/null; echo "@reboot cd /home/ubuntu/smartwalker/docker && docker compose -f docker-compose.prod.yml up -d") | crontab -
```

> Elastic IP는 인스턴스가 중지된 동안에도 할당만 되어 있으면 무료입니다.
> 단, 할당 후 아무 인스턴스에도 연결하지 않으면 $0.005/hr 부과됩니다.

---

## 10. 모니터링

CloudWatch 기본 메트릭(CPU, 네트워크)은 무료입니다.

```bash
# EC2에서 직접 확인
docker stats
df -h
free -h
```

디스크 사용량 알림 (CloudWatch Alarm):
- 임계값: EBS 80% 이상 → SNS → 이메일 알림
- 콘솔 → CloudWatch → Alarms → Create Alarm → EC2 → DiskSpaceUtilization
