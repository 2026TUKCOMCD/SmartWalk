# Google Colab에서 실행하세요: https://colab.research.google.com
# 이 스크립트는 YOLOv8n 모델을 TFLite 형식으로 변환합니다.
#
# 사용 방법:
# 1. Google Colab (https://colab.research.google.com)에 접속
# 2. 새 노트북 생성
# 3. 아래 코드를 셀에 복사하여 실행
# 4. 생성된 yolov8n_float32.tflite 파일을 다운로드
# 5. android/app/src/main/assets/ 폴더에 yolov8n.tflite로 이름 변경하여 복사

# Cell 1: Install dependencies
!pip install ultralytics

# Cell 2: Export model to TFLite
from ultralytics import YOLO
from google.colab import files

# Load YOLOv8n model (will download automatically)
model = YOLO('yolov8n.pt')

# Export to TFLite format
# This creates yolov8n_saved_model/yolov8n_float32.tflite
result = model.export(format='tflite', imgsz=640)
print(f"Export completed: {result}")

# Cell 3: Download the TFLite file
import shutil
import os

# Find and rename the TFLite file
tflite_path = 'yolov8n_saved_model/yolov8n_float32.tflite'
if os.path.exists(tflite_path):
    shutil.copy(tflite_path, 'yolov8n.tflite')
    files.download('yolov8n.tflite')
    print("Download started!")
else:
    print("TFLite file not found. Check the export output above.")
    # List available files
    for root, dirs, filenames in os.walk('.'):
        for f in filenames:
            if f.endswith('.tflite'):
                print(f"Found: {os.path.join(root, f)}")
