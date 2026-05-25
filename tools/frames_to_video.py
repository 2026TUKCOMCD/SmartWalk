#!/usr/bin/env python3
"""
frames_to_video.py — sessions/<id>/frames/*.jpg 를 MP4 동영상으로 변환.

frames.csv 의 실제 타임스탬프를 읽어 촬영 간격을 그대로 재현한다 (가변 FPS).
frames.csv 가 없으면 --fps 옵션의 고정 FPS 로 변환한다.

사용법:
  python tools/frames_to_video.py sessions/<id>
  python tools/frames_to_video.py sessions/<id> --fps 15
  python tools/frames_to_video.py sessions/<id> --out preview.mp4
  python tools/frames_to_video.py sessions/<id> --fps 0   # 고정 FPS 강제 (기본 15)

출력:
  sessions/<id>/preview.mp4

의존:
  ffmpeg (PATH에 있어야 함)
"""

import argparse
import csv
import os
import subprocess
import sys
import tempfile
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser(description="JPEG 프레임 시퀀스를 MP4로 변환")
    p.add_argument("session_dir", help="세션 디렉터리 (sessions/<id>)")
    p.add_argument("--fps", type=float, default=15.0,
                   help="frames.csv 없을 때 사용할 고정 FPS (기본 15)")
    p.add_argument("--out", default="preview.mp4",
                   help="출력 파일명 (세션 디렉터리 기준, 기본 preview.mp4)")
    p.add_argument("--force-fps", action="store_true",
                   help="frames.csv 가 있어도 --fps 고정값 사용")
    return p.parse_args()


def check_ffmpeg():
    try:
        subprocess.run(["ffmpeg", "-version"], capture_output=True, check=True)
    except (FileNotFoundError, subprocess.CalledProcessError):
        print("오류: ffmpeg 가 PATH에 없습니다.", file=sys.stderr)
        print("  설치: https://ffmpeg.org/download.html  또는  winget install ffmpeg", file=sys.stderr)
        sys.exit(1)


def load_timestamps(csv_path: Path) -> list[int]:
    """frames.csv 에서 timestampMs 컬럼을 읽어 정렬 반환."""
    timestamps = []
    with open(csv_path, newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            timestamps.append(int(row["timestampMs"]))
    return timestamps


def collect_frames(frames_dir: Path) -> list[Path]:
    frames = sorted(frames_dir.glob("frame_*.jpg"),
                    key=lambda p: int(p.stem.split("_")[1]))
    if not frames:
        print(f"오류: {frames_dir} 에 frame_*.jpg 가 없습니다.", file=sys.stderr)
        sys.exit(1)
    return frames


def build_concat_file(frames: list[Path], durations: list[float], tmp_path: str):
    """ffmpeg concat demuxer 입력 파일 생성."""
    with open(tmp_path, "w") as f:
        f.write("ffconcat version 1.0\n")
        for frame, dur in zip(frames, durations):
            # 절대 경로 사용 (concat 파일이 tmp 디렉터리에 있으므로)
            f.write(f"file '{frame.resolve()}'\n")
            f.write(f"duration {dur:.6f}\n")
        # ffmpeg concat demuxer 는 마지막 프레임의 duration 을 무시하므로
        # 마지막 프레임을 한 번 더 써서 정상 종료를 보장
        if frames:
            f.write(f"file '{frames[-1].resolve()}'\n")


def durations_from_timestamps(timestamps: list[int], n_frames: int) -> list[float]:
    """타임스탬프 리스트 → 프레임별 duration(초) 리스트."""
    if len(timestamps) != n_frames:
        print(f"경고: frames.csv 행 수({len(timestamps)})와 프레임 수({n_frames})가 다릅니다. "
              "앞부터 맞는 수만 사용합니다.", file=sys.stderr)
        count = min(len(timestamps), n_frames)
        timestamps = timestamps[:count]
        n_frames = count

    durations = []
    for i in range(n_frames - 1):
        diff_ms = timestamps[i + 1] - timestamps[i]
        # 음수나 0 방지 (클럭 보정 등으로 드물게 발생)
        dur = max(diff_ms / 1000.0, 0.001)
        durations.append(dur)
    # 마지막 프레임은 직전 프레임과 동일한 duration
    if durations:
        durations.append(durations[-1])
    else:
        durations.append(1.0 / 15.0)

    return durations


def run_ffmpeg_concat(concat_file: str, output: Path):
    cmd = [
        "ffmpeg", "-y",
        "-f", "concat",
        "-safe", "0",
        "-i", concat_file,
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",  # H.264 짝수 해상도 보정
        "-c:v", "libx264",
        "-preset", "fast",
        "-crf", "23",
        "-pix_fmt", "yuv420p",  # 범용 플레이어 호환
        str(output),
    ]
    print("실행:", " ".join(cmd))
    result = subprocess.run(cmd)
    if result.returncode != 0:
        print("오류: ffmpeg 변환 실패.", file=sys.stderr)
        sys.exit(1)


def run_ffmpeg_fixed_fps(frames_dir: Path, fps: float, output: Path):
    """고정 FPS 모드: ffmpeg image2 demuxer 사용 (concat 불필요, 빠름)."""
    pattern = str(frames_dir / "frame_%05d.jpg")
    cmd = [
        "ffmpeg", "-y",
        "-framerate", str(fps),
        "-i", pattern,
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
        "-c:v", "libx264",
        "-preset", "fast",
        "-crf", "23",
        "-pix_fmt", "yuv420p",
        str(output),
    ]
    print("실행:", " ".join(cmd))
    result = subprocess.run(cmd)
    if result.returncode != 0:
        print("오류: ffmpeg 변환 실패.", file=sys.stderr)
        sys.exit(1)


def main():
    args = parse_args()
    check_ffmpeg()

    session_dir = Path(args.session_dir)
    if not session_dir.is_dir():
        print(f"오류: {session_dir} 가 존재하지 않습니다.", file=sys.stderr)
        sys.exit(1)

    frames_dir = session_dir / "frames"
    csv_path = session_dir / "frames.csv"
    output = session_dir / args.out

    frames = collect_frames(frames_dir)
    print(f"프레임 수: {len(frames)}")

    use_timestamps = csv_path.exists() and not args.force_fps
    if use_timestamps:
        print(f"frames.csv 발견 → 실제 타임스탬프 기반 가변 FPS 사용")
        timestamps = load_timestamps(csv_path)
        durations = durations_from_timestamps(timestamps, len(frames))

        elapsed = sum(durations)
        avg_fps = len(frames) / elapsed if elapsed > 0 else 0
        print(f"총 재생 시간: {elapsed:.1f}s, 평균 FPS: {avg_fps:.1f}")

        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False, prefix="navblind_concat_"
        ) as tmp:
            tmp_path = tmp.name

        try:
            build_concat_file(frames, durations, tmp_path)
            run_ffmpeg_concat(tmp_path, output)
        finally:
            os.unlink(tmp_path)

    else:
        fps = args.fps if args.fps > 0 else 15.0
        if not csv_path.exists():
            print(f"frames.csv 없음 → 고정 FPS {fps} 사용")
        else:
            print(f"--force-fps 지정 → 고정 FPS {fps} 사용")
        run_ffmpeg_fixed_fps(frames_dir, fps, output)

    print(f"\n완료: {output}")


if __name__ == "__main__":
    main()
