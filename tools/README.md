# NavBlind 테스트 인프라

실제 폰으로 한 번 데이터를 수집한 뒤, 집에서 에뮬레이터로 반복 테스트하기 위한 도구 모음.

## 전체 흐름

```
[밖에서 — 실제 폰]              [집에서 — PC + 에뮬레이터]
────────────────────            ────────────────────────────────
앱 실행                         bash tools/collect_session.sh
  → 데이터 수집 시작 버튼  →→→  python tools/mock_stream.py sessions/<id>
  → GPS 자동 기록                Android Studio → 에뮬레이터 GPX 로드
  → ESP32-CAM 영상 자동 기록     앱 실행 → 에뮬레이터에서 동일하게 동작
  → 데이터 수집 중지
```

---

## Step 1: 밖에서 데이터 수집

앱에서 데이터 수집을 시작한다:

```kotlin
// 아무 Activity에서 호출
DataCollectionService.start(context, glassStreamUrl = "http://192.168.4.1/stream")

// 돌아왔을 때 중지
DataCollectionService.stop(context)
```

수집되는 파일:
```
/sdcard/Android/data/com.navblind/files/sessions/<timestamp>/
  route.gpx        ← GPS 궤적 (에뮬레이터 GPX 재생용)
  frames/
    frame_00000.jpg
    frame_00001.jpg
    ...
  frames.csv       ← 프레임 타이밍 정보
  meta.txt         ← 세션 요약
```

---

## Step 2: 폰 → PC 복사

USB 연결 후:

```bash
# 최신 세션 1개 복사
bash tools/collect_session.sh

# 모든 세션 복사
bash tools/collect_session.sh --all

# 폰의 세션 목록만 확인
bash tools/collect_session.sh --list
```

---

## Step 3: PC에서 Mock 스트림 서버 실행

```bash
# 설치 (최초 1회)
pip install opencv-python   # 필요 없음 — 순수 stdlib 사용

# 최신 세션으로 서버 시작
python tools/mock_stream.py sessions/$(ls sessions | sort | tail -1)

# 옵션
python tools/mock_stream.py sessions/<id> --fps 10     # 느리게
python tools/mock_stream.py sessions/<id> --fps 0      # 원본 타이밍
python tools/mock_stream.py sessions/<id> --once       # 한 번만 재생
python tools/mock_stream.py sessions/<id> --port 9090  # 포트 변경
```

에뮬레이터에서 접속 URL: `http://10.0.2.2:8081/stream`
실제 기기에서 접속 URL: `http://<PC_IP>:8081/stream`

---

## Step 4: 에뮬레이터 GPS 재생

Android Studio → 에뮬레이터 → `...` (Extended Controls) → **Location** 탭:

1. **Routes** 탭 클릭
2. **Import GPX/KML** → `sessions/<id>/route.gpx` 선택
3. **Play Route** 클릭 (속도 조절 가능)

이러면 에뮬레이터의 GPS가 녹화한 경로대로 움직임.

---

## 빌드 설정

`build.gradle.kts` 의 `GLASS_STREAM_URL` 이 빌드 타입별로 자동 분기된다:

| 빌드 타입 | GLASS_STREAM_URL |
|---|---|
| debug | `http://10.0.2.2:8080/stream` (mock 서버) |
| release | `http://192.168.4.1/stream` (실제 ESP32-CAM) |

앱 코드에서:
```kotlin
val streamUrl = BuildConfig.GLASS_STREAM_URL
```

---

## TFLite 에뮬레이터 설정

에뮬레이터는 x86_64 아키텍처이므로 GPU delegate 를 끄고 실행:

```kotlin
val options = Interpreter.Options().apply {
    useNNAPI = false   // 에뮬레이터에서 NNAPI 비활성화
    numThreads = 4
}
```

`build.gradle.kts` 의 `ndk { abiFilters }` 에 `x86_64` 가 이미 포함되어 있다.
