#!/usr/bin/env python3
"""
수집된 세션의 카메라 프레임을 일괄 회전시킵니다.

사용법:
    python tools/rotate_frames.py sessions/<timestamp>      # 특정 세션
    python tools/rotate_frames.py sessions/<timestamp> --dry-run  # 실제 저장 없이 확인만
"""

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    print("오류: Pillow가 설치되어 있지 않습니다.")
    print("    pip install Pillow")
    sys.exit(1)


def rotate_frames(session_dir: Path, dry_run: bool = False) -> None:
    frames_dir = session_dir / "frames"

    if not frames_dir.exists():
        print(f"오류: frames 폴더가 없습니다 → {frames_dir}")
        sys.exit(1)

    jpgs = sorted(frames_dir.glob("*.jpg"))
    if not jpgs:
        print(f"오류: {frames_dir} 에 JPG 파일이 없습니다")
        sys.exit(1)

    print(f"세션: {session_dir.name}")
    print(f"프레임 수: {len(jpgs)}개")
    print(f"회전 방향: 시계 방향 90° (윗면 → 오른면)")
    if dry_run:
        print("[dry-run] 실제 저장은 하지 않습니다\n")
    else:
        print()

    for i, jpg_path in enumerate(jpgs):
        if dry_run:
            print(f"  [{i+1}/{len(jpgs)}] {jpg_path.name} → (저장 생략)")
            continue

        img = Image.open(jpg_path)

        # 시계 방향 90° 회전: 윗면이 오른면으로
        # PIL.rotate()는 반시계 방향이므로 -90 또는 ROTATE_270 사용
        rotated = img.transpose(Image.Transpose.ROTATE_270)

        rotated.save(jpg_path, "JPEG", quality=85)
        img.close()
        rotated.close()

        if (i + 1) % 50 == 0 or (i + 1) == len(jpgs):
            print(f"  진행: {i+1}/{len(jpgs)}")

    if not dry_run:
        print(f"\n완료: {len(jpgs)}개 프레임 회전 저장됨")


def main() -> None:
    parser = argparse.ArgumentParser(description="세션 프레임 일괄 회전 (시계 방향 90°)")
    parser.add_argument("session_dir", help="세션 폴더 경로 (예: sessions/1771662261949)")
    parser.add_argument("--dry-run", action="store_true", help="실제 저장 없이 확인만")
    args = parser.parse_args()

    session_dir = Path(args.session_dir)
    if not session_dir.exists():
        print(f"오류: 세션 폴더가 없습니다 → {session_dir}")
        sys.exit(1)

    rotate_frames(session_dir, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
