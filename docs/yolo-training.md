# YOLO 커스텀 학습 가이드 (Google Colab)

## 목표 클래스

| ID | 클래스명 | 설명 |
|----|----------|------|
| 0 | `traffic_light_red` | 적색 신호등 |
| 1 | `traffic_light_green` | 녹색 신호등 |
| 2 | `crosswalk` | 횡단보도 |
| 3 | `obstacle` | 보행 장애물 |
| 4 | `stairs` | 계단 |
| 5 | `tactile_paving` | 점자블록 |

## 데이터셋 출처

| 데이터셋 | workspace / project | 이미지 수 | 커버 클래스 |
|----------|---------------------|-----------|-------------|
| Road Signs & Traffic Lights (color) | `internship-tvkfo` / `road-signs-and-traffic-lights-detection-and-color-recognition-using-yolov8` | 2,133 | 신호등(적/녹) |
| Pedestrian Traffic Light | `ono-gedd7` / `pedestrian-traffic-light-puf4a` | 926 | 신호등(적/녹) |
| YOLOv8 Crosswalk Detection | `tcc-xn3st` / `yolov8-crosswalk-detection` | 1,049 | 횡단보도 |
| Braille Blocks Detection | `tong-gae` / `braille-blocks-detection` | 638 | 점자블록 |
| Braille Block (large) | `braille-block-qdtxl` / `braille-block-f7vqq` | 1,872 | 점자블록 |
| Tactile Paving Detection | `ods-8nj8c` / `tactile-paving-detection-analz` | 206 | 점자블록 |
| Open Images v7 | fiftyone API | ~1,500 | 신호등·횡단보도·계단 |

---

## Colab 노트북

아래 셀을 순서대로 새 Colab 노트북에 붙여넣어 실행합니다.
런타임 → 런타임 유형 변경 → **T4 GPU** 선택 후 시작하세요.

---

### 셀 1 — 패키지 설치 및 Drive 마운트

```python
!pip install -q ultralytics roboflow fiftyone

from google.colab import drive
drive.mount('/content/drive')

import os, shutil, yaml, random
from pathlib import Path
from collections import defaultdict, Counter

DRIVE_DIR = "/content/drive/MyDrive/smartwalker_yolo"
os.makedirs(DRIVE_DIR, exist_ok=True)
print("준비 완료")
```

---

### 셀 2 — 설정 (API 키만 여기서 입력)

```python
# Roboflow 계정의 Private API Key
# roboflow.com → 우상단 프로필 → Roboflow API → copy
ROBOFLOW_API_KEY = "여기에_API_키_입력"

# 우리 클래스 정의
CLASS_NAMES = [
    "traffic_light_red",    # 0
    "traffic_light_green",  # 1
    "crosswalk",            # 2
    "obstacle",             # 3
    "stairs",               # 4
    "tactile_paving",       # 5
]
NC = len(CLASS_NAMES)

# 소스별 클래스명 → 우리 ID 매핑
# 각 데이터셋의 실제 클래스명을 확인 후 여기서 추가/수정
CLASS_REMAP = {
    # 신호등
    "red": 0, "red_light": 0, "traffic_light_red": 0,
    "stop": 0, "red light": 0,
    "green": 1, "green_light": 1, "traffic_light_green": 1,
    "go": 1, "green light": 1,
    # 횡단보도
    "crosswalk": 2, "crosswalk_line": 2, "zebra_crossing": 2,
    "pedestrian crossing": 2, "zebra crossing": 2,
    # 계단
    "stairs": 4, "staircase": 4, "steps": 4,
    # 점자블록 (tactile-paving-detection-analz 의 3개 클래스 포함)
    "tactile_paving": 5, "braille_block": 5, "braille block": 5,
    "tactile": 5, "braille": 5,
    "horizontal-directional-tile": 5,
    "vertical-directional-tile": 5,
    "warning-tile": 5,
    "directional tile": 5, "warning tile": 5,
}

DATASET_ROOT = Path("/content/dataset")
MERGED_DIR   = Path("/content/dataset/merged")
print("설정 완료")
```

---

### 셀 3 — Roboflow 데이터셋 다운로드

```python
from roboflow import Roboflow

rf = Roboflow(api_key=ROBOFLOW_API_KEY)

def download_latest(workspace: str, project_name: str, dest: str) -> str:
    """최신 버전을 YOLOv8 포맷으로 다운로드하고 경로를 반환."""
    project = rf.workspace(workspace).project(project_name)
    versions = project.versions()
    if not versions:
        raise RuntimeError(f"버전 없음: {workspace}/{project_name}")
    latest_ver = max(v.version for v in versions)
    print(f"  {project_name}: v{latest_ver} 다운로드 중...")
    ds = project.version(latest_ver).download("yolov8", location=dest)
    print(f"  → {dest} ({len(list(Path(dest).rglob('*.jpg')) + list(Path(dest).rglob('*.png')))}장)")
    return dest

ROBOFLOW_DATASETS = [
    # (workspace, project, 로컬 저장 폴더명)
    (
        "internship-tvkfo",
        "road-signs-and-traffic-lights-detection-and-color-recognition-using-yolov8",
        "traffic_light_color",
    ),
    (
        "ono-gedd7",
        "pedestrian-traffic-light-puf4a",
        "pedestrian_traffic_light",
    ),
    (
        "tcc-xn3st",
        "yolov8-crosswalk-detection",
        "crosswalk",
    ),
    (
        "tong-gae",
        "braille-blocks-detection",
        "braille_blocks",
    ),
    (
        "braille-block-qdtxl",
        "braille-block-f7vqq",
        "braille_block_large",
    ),
    (
        "ods-8nj8c",
        "tactile-paving-detection-analz",
        "tactile_paving",
    ),
]

roboflow_dirs = []
for workspace, project, folder in ROBOFLOW_DATASETS:
    dest = str(DATASET_ROOT / "roboflow" / folder)
    try:
        download_latest(workspace, project, dest)
        roboflow_dirs.append(dest)
    except Exception as e:
        print(f"  ⚠ {project} 실패: {e}")

print(f"\n다운로드 완료: {len(roboflow_dirs)}개")
```

---

### 셀 4 — Open Images v7 다운로드 (신호등·횡단보도·계단 보강)

```python
import fiftyone as fo
import fiftyone.zoo as foz

OI_CLASSES = ["Traffic light", "Zebra crossing", "Stairs"]
OI_EXPORT_DIR = str(DATASET_ROOT / "open_images")

oi_dataset = foz.load_zoo_dataset(
    "open-images-v7",
    split="train",
    label_types=["detections"],
    classes=OI_CLASSES,
    max_samples=600,   # 클래스당 최대 200장
    seed=42,
    dataset_name="oi_smartwalker",
)

oi_dataset.export(
    export_dir=OI_EXPORT_DIR,
    dataset_type=fo.types.YOLOv5Dataset,
    label_field="detections",
    classes=OI_CLASSES,
)

# Open Images 클래스 ID 매핑 (색상 구분 없으므로 traffic_light_red(0)로 통일)
OI_REMAP = {
    OI_CLASSES.index("Traffic light"):   0,  # → traffic_light_red (색상 정보 없음)
    OI_CLASSES.index("Zebra crossing"):  2,  # → crosswalk
    OI_CLASSES.index("Stairs"):          4,  # → stairs
}

def remap_oi_labels(export_dir: str, id_remap: dict):
    for split in ["train", "val"]:
        lbl_dir = Path(export_dir) / split / "labels"
        if not lbl_dir.exists():
            continue
        for txt in lbl_dir.glob("*.txt"):
            new_lines = []
            for line in txt.read_text().strip().splitlines():
                parts = line.split()
                src_id = int(parts[0])
                if src_id in id_remap:
                    new_lines.append(f"{id_remap[src_id]} {' '.join(parts[1:])}")
            txt.write_text("\n".join(new_lines))

remap_oi_labels(OI_EXPORT_DIR, OI_REMAP)
print(f"Open Images 완료: {OI_EXPORT_DIR}")
```

---

### 셀 5 — Roboflow 클래스 재매핑

각 데이터셋의 클래스명을 우리 ID로 통일합니다.

```python
def load_yaml_names(dataset_dir: str) -> list[str]:
    for yaml_name in ["data.yaml", "dataset.yaml", "_annotations.yaml"]:
        p = Path(dataset_dir) / yaml_name
        if p.exists():
            with open(p) as f:
                meta = yaml.safe_load(f)
            names = meta.get("names", [])
            return names if isinstance(names, list) else list(names.values())
    return []

def remap_roboflow(dataset_dir: str):
    src_names = load_yaml_names(dataset_dir)
    if not src_names:
        print(f"  ⚠ YAML 없음: {dataset_dir}")
        return

    id_remap = {}
    for src_id, name in enumerate(src_names):
        key = name.lower().strip()
        if key in CLASS_REMAP:
            id_remap[src_id] = CLASS_REMAP[key]
        else:
            # 부분 매칭 fallback
            for pattern, our_id in CLASS_REMAP.items():
                if pattern in key or key in pattern:
                    id_remap[src_id] = our_id
                    break

    print(f"  {Path(dataset_dir).name}: {dict(zip(src_names, [id_remap.get(i,'skip') for i in range(len(src_names))]))}")

    for split in ["train", "valid", "val", "test"]:
        lbl_dir = Path(dataset_dir) / split / "labels"
        if not lbl_dir.exists():
            continue
        for txt in lbl_dir.glob("*.txt"):
            new_lines = []
            for line in txt.read_text().strip().splitlines():
                parts = line.split()
                if not parts:
                    continue
                src_id = int(parts[0])
                if src_id in id_remap:
                    new_lines.append(f"{id_remap[src_id]} {' '.join(parts[1:])}")
            txt.write_text("\n".join(new_lines))

print("클래스 재매핑 시작...")
for d in roboflow_dirs:
    remap_roboflow(d)
print("완료")
```

---

### 셀 6 — 데이터셋 병합 및 분할

```python
ALL_SOURCES = roboflow_dirs + [OI_EXPORT_DIR]

def collect_pairs(src_dir: str) -> list[tuple]:
    """(이미지 경로, 라벨 경로) 유효한 쌍만 반환."""
    pairs = []
    for split in ["train", "valid", "val", "test"]:
        img_dir = Path(src_dir) / split / "images"
        lbl_dir = Path(src_dir) / split / "labels"
        if not img_dir.exists():
            continue
        for img in sorted(img_dir.glob("*.[jp][pn]g")):
            lbl = lbl_dir / img.with_suffix(".txt").name
            if lbl.exists() and lbl.stat().st_size > 0:
                pairs.append((img, lbl))
    return pairs

all_pairs = []
for src in ALL_SOURCES:
    pairs = collect_pairs(src)
    print(f"  {Path(src).name}: {len(pairs)}쌍")
    all_pairs.extend(pairs)

print(f"\n전체: {len(all_pairs)}쌍")

# 8:1.5:0.5 분할
random.seed(42)
random.shuffle(all_pairs)
n = len(all_pairs)
n_train = int(n * 0.80)
n_val   = int(n * 0.15)

split_assignments = (
    [(p, "train") for p in all_pairs[:n_train]]
    + [(p, "val")   for p in all_pairs[n_train:n_train + n_val]]
    + [(p, "test")  for p in all_pairs[n_train + n_val:]]
)

for split in ["train", "val", "test"]:
    (MERGED_DIR / split / "images").mkdir(parents=True, exist_ok=True)
    (MERGED_DIR / split / "labels").mkdir(parents=True, exist_ok=True)

for (img, lbl), split in split_assignments:
    # 파일명 충돌 방지: 소스 폴더명 prefix 추가
    prefix = Path(img).parts[-4]  # .../roboflow/<folder>/train/images/<file>
    dst_name = f"{prefix}_{img.name}"
    shutil.copy(img, MERGED_DIR / split / "images" / dst_name)
    shutil.copy(lbl, MERGED_DIR / split / "labels" / Path(dst_name).with_suffix(".txt").name)

print(f"병합 완료 — train:{n_train} / val:{n_val} / test:{n - n_train - n_val}")

# data.yaml 작성
data_yaml = {
    "path":  str(MERGED_DIR),
    "train": "train/images",
    "val":   "val/images",
    "test":  "test/images",
    "nc":    NC,
    "names": CLASS_NAMES,
}
with open(MERGED_DIR / "data.yaml", "w") as f:
    yaml.dump(data_yaml, f, allow_unicode=True, default_flow_style=False)
print("data.yaml 생성 완료")
```

---

### 셀 7 — 클래스 분포 확인

```python
counter = Counter()
for txt in (MERGED_DIR / "train" / "labels").glob("*.txt"):
    for line in txt.read_text().strip().splitlines():
        parts = line.split()
        if parts:
            counter[int(parts[0])] += 1

print(f"{'ID':<4} {'클래스':<25} {'개수':>6}  {'상태'}")
print("-" * 50)
for cls_id, name in enumerate(CLASS_NAMES):
    count = counter.get(cls_id, 0)
    status = "✓" if count >= 200 else ("△ 부족" if count >= 50 else "✗ 매우 부족")
    print(f"{cls_id:<4} {name:<25} {count:>6}  {status}")
```

200개 미만(△)인 클래스는 Roboflow Universe에서 추가 데이터셋을 찾아 셀 3에 추가 후 재실행합니다.

---

### 셀 8 — 학습

```python
from ultralytics import YOLO

model = YOLO("yolov8n.pt")   # nano — 모바일 최적화

results = model.train(
    data    = str(MERGED_DIR / "data.yaml"),
    epochs  = 100,
    imgsz   = 640,
    batch   = 16,           # OOM 시 8로 줄임
    name    = "smartwalker_v1",
    patience= 20,
    augment = True,
    hsv_h   = 0.01,         # 신호등 색상 오인식 방지 — 색조 augment 최소화
    hsv_s   = 0.5,
    hsv_v   = 0.4,
    project = f"{DRIVE_DIR}/runs",   # Drive에 직접 저장 (세션 끊겨도 유지)
)

BEST_PT = f"{DRIVE_DIR}/runs/smartwalker_v1/weights/best.pt"
print(f"학습 완료: {BEST_PT}")
```

---

### 셀 9 — 성능 확인

```python
model = YOLO(BEST_PT)
metrics = model.val(data=str(MERGED_DIR / "data.yaml"))

print(f"\n{'ID':<4} {'클래스':<25} {'mAP50':>7} {'mAP50-95':>10}  상태")
print("-" * 56)
for i, name in enumerate(CLASS_NAMES):
    ap50 = float(metrics.box.ap50[i]) if i < len(metrics.box.ap50) else 0.0
    ap   = float(metrics.box.ap[i])   if i < len(metrics.box.ap)   else 0.0
    flag = "✓" if ap50 >= 0.65 else ("△" if ap50 >= 0.40 else "✗ 재학습 필요")
    print(f"{i:<4} {name:<25} {ap50:>7.3f} {ap:>10.3f}  {flag}")

print(f"\n전체 mAP50: {metrics.box.map50:.3f}  (목표 ≥ 0.65)")
```

---

### 셀 10 — TFLite 변환 및 Drive 저장

```python
model = YOLO(BEST_PT)

# INT8 양자화 (권장: 모델 ~4MB, Pixel 6 기준 추론 ~30ms)
model.export(
    format = "tflite",
    imgsz  = 640,
    int8   = True,
    data   = str(MERGED_DIR / "data.yaml"),
)

# 변환 파일 Drive에 복사
import glob as _glob

tflite_files = _glob.glob(
    str(Path(BEST_PT).parent.parent / "**/*.tflite"),
    recursive=True,
)
for f in tflite_files:
    dst = f"{DRIVE_DIR}/{Path(f).name}"
    shutil.copy(f, dst)
    print(f"저장됨: {dst}")

# labels.txt도 함께 저장
labels_path = f"{DRIVE_DIR}/labels.txt"
Path(labels_path).write_text("\n".join(CLASS_NAMES))
print(f"저장됨: {labels_path}")
```

INT8 후 mAP가 5% 이상 떨어지면 FP16으로 재시도:

```python
model.export(format="tflite", imgsz=640, int8=False, half=True)
```

---

## Android 앱 적용

Drive에서 `.tflite`와 `labels.txt`를 로컬로 다운로드한 뒤:

```
android/app/src/main/assets/yolov8n.tflite   ← tflite 파일 교체
android/app/src/main/assets/labels.txt       ← labels.txt 교체
```

`DetectionLabels.kt`에서 위험 클래스 목록 확인:
```kotlin
val DANGER_CLASSES = setOf("obstacle", "stairs", "traffic_light_red")
```
