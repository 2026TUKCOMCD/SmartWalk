# Firebase 설정 가이드

## 프로젝트 생성

1. [Firebase 콘솔](https://console.firebase.google.com) → 새 프로젝트 생성
2. Google 애널리틱스는 선택 사항 (비활성화해도 무방)

---

## Android 앱 등록

1. 콘솔 → 프로젝트 개요 → Android 앱 추가
2. Android 패키지 이름: `com.smartwalker`
3. 앱 닉네임: `SmartWalker`
4. SHA-1 인증서 지문 (전화번호 인증에 필요):
   ```bash
   cd android
   ./gradlew signingReport
   # debug 섹션의 SHA1 값 복사
   ```
5. `google-services.json` 다운로드 → `android/app/google-services.json` 에 저장

> `android/app/google-services.json`은 `.gitignore`에 등록되어 있지 않습니다. 공개 저장소라면 `.gitignore`에 추가하세요.

---

## Phone Auth 활성화

콘솔 → Authentication → Sign-in method → 전화 활성화

테스트 전화번호 등록 (SMS 없이 테스트 가능):
- 콘솔 → Authentication → Sign-in method → 전화 → 테스트 전화번호
- 예: `+82 10-0000-0000`, 인증코드 `123456`

---

## 백엔드 Admin SDK 키

1. 콘솔 → 프로젝트 설정 → 서비스 계정
2. "새 비공개 키 생성" → JSON 다운로드
3. 파일 이름을 `smartwalker-firebase-adminsdk-key.json`으로 변경
4. `backend/` 디렉토리에 저장

```
backend/
└── smartwalker-firebase-adminsdk-key.json   ← 여기
```

> 이 파일에는 개인 키가 포함되어 있습니다. `backend/.gitignore`에 등록되어 있으나 절대 커밋하지 마세요.

---

## 동작 확인

### Android

앱 실행 → 전화번호 입력 → SMS 인증 코드 입력 → 메인 화면 진입

### 백엔드

```bash
# Firebase 활성화 상태로 실행
npm run backend:dev

# 토큰 검증 테스트 (Android에서 발급받은 ID 토큰 사용)
curl -X POST http://localhost:8080/v1/auth/verify \
  -H "Authorization: Bearer <ID_TOKEN>"
```

---

## 개발 중 Firebase 인증 건너뛰기

백엔드만 개발할 때 Firebase 없이 테스트:

```bash
FIREBASE_DISABLED=true npm run backend:dev
```

이 모드에서는 `X-User-Id` 헤더로 사용자 UUID를 직접 전달합니다:

```bash
curl -X POST http://localhost:8080/v1/navigation/route \
  -H "X-User-Id: 00000000-0000-0000-0000-000000000001" \
  -H "Content-Type: application/json" \
  -d '{"originLat": 37.5, "originLng": 127.0, "destLat": 37.51, "destLng": 127.01}'
```

---

## 주의사항

- Admin SDK 키는 1개만 발급 받아도 여러 환경에서 사용 가능합니다
- 키가 유출되었다면 콘솔 → 서비스 계정 → 해당 키 비활성화 후 새 키 발급
- `google-services.json`은 공개되어도 큰 문제 없으나, Admin SDK 키는 서버측 비밀로 관리해야 합니다
