#!/usr/bin/env python3
"""
NavBlind Mock MJPEG Stream Server
===================================
collect_session.sh 로 PC에 받아온 세션의 JPEG 프레임을 MJPEG 스트림으로 재생한다.
에뮬레이터에서 앱이 http://10.0.2.2:8080/stream 에 접속하면 실제 ESP32-CAM 처럼 동작한다.

사용법:
  python tools/mock_stream.py sessions/<timestamp>
  python tools/mock_stream.py sessions/<timestamp> --port 8080 --fps 15 --loop

옵션:
  --port  : 서버 포트 (기본 8080)
  --fps   : 재생 FPS (기본 15, 원본 타이밍 사용 시 0 지정)
  --loop  : 영상 끝나면 처음부터 반복 (기본 활성화)
  --once  : 한 번만 재생 후 종료
"""

import argparse
import csv
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Lock

BOUNDARY = b"navblind_frame"


class Session:
    """세션 디렉토리에서 프레임 목록과 타이밍을 로드한다."""

    def __init__(self, session_dir: Path):
        self.frames_dir = session_dir / "frames"
        self.meta_csv = session_dir / "frames.csv"

        if not self.frames_dir.exists():
            raise FileNotFoundError(f"frames/ 디렉토리가 없습니다: {self.frames_dir}")

        self.frame_paths = sorted(self.frames_dir.glob("frame_*.jpg"))
        if not self.frame_paths:
            raise FileNotFoundError(f"JPEG 프레임이 없습니다: {self.frames_dir}")

        # 원본 타이밍 로드 (있으면)
        self.timestamps: list[int] = []
        if self.meta_csv.exists():
            with open(self.meta_csv, newline="") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    self.timestamps.append(int(row["timestamp_ms"]))

        print(f"[Session] {len(self.frame_paths)}개 프레임 로드 완료 ({session_dir.name})")

    def frame_count(self) -> int:
        return len(self.frame_paths)

    def get_frame_bytes(self, index: int) -> bytes:
        return self.frame_paths[index].read_bytes()

    def original_delay(self, index: int) -> float:
        """원본 녹화 기준 다음 프레임까지의 지연 (초). 타이밍 정보 없으면 None."""
        if len(self.timestamps) < 2 or index + 1 >= len(self.timestamps):
            return None
        return (self.timestamps[index + 1] - self.timestamps[index]) / 1000.0


class FrameIterator:
    """스레드 안전 프레임 이터레이터."""

    def __init__(self, session: Session, fps: float, loop: bool):
        self.session = session
        self.fps = fps          # 0 이면 원본 타이밍 사용
        self.loop = loop
        self._index = 0
        self._lock = Lock()

    def next_frame(self) -> tuple[bytes, float] | None:
        """(jpeg_bytes, delay_seconds) 반환. 스트림 끝이면 None."""
        with self._lock:
            idx = self._index
            if idx >= self.session.frame_count():
                if not self.loop:
                    return None
                self._index = 0
                idx = 0
            data = self.session.get_frame_bytes(idx)
            if self.fps > 0:
                delay = 1.0 / self.fps
            else:
                delay = self.session.original_delay(idx) or (1.0 / 15)
            self._index += 1
            return data, delay


# 전역 이터레이터 (모든 클라이언트가 공유)
_frame_iter: FrameIterator | None = None


class MjpegHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        print(f"[{self.address_string()}] {format % args}")

    def do_GET(self):
        if self.path not in ("/stream", "/"):
            self.send_error(404)
            return

        self.send_response(200)
        self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY.decode()}")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        print(f"[클라이언트 연결] {self.client_address}")
        try:
            while True:
                result = _frame_iter.next_frame()
                if result is None:
                    break
                jpeg, delay = result

                part_header = (
                    f"--{BOUNDARY.decode()}\r\n"
                    f"Content-Type: image/jpeg\r\n"
                    f"Content-Length: {len(jpeg)}\r\n"
                    f"\r\n"
                ).encode()

                self.wfile.write(part_header)
                self.wfile.write(jpeg)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
                time.sleep(delay)
        except (BrokenPipeError, ConnectionResetError):
            print(f"[클라이언트 종료] {self.client_address}")


def main():
    parser = argparse.ArgumentParser(description="NavBlind Mock MJPEG Server")
    parser.add_argument("session_dir", help="세션 디렉토리 경로 (예: sessions/1708412800000)")
    parser.add_argument("--port", type=int, default=8081, help="서버 포트 (기본 8081)")
    parser.add_argument("--fps", type=float, default=15, help="재생 FPS (0=원본 타이밍)")
    parser.add_argument("--once", action="store_true", help="한 번만 재생 후 종료")
    args = parser.parse_args()

    session_path = Path(args.session_dir)
    if not session_path.exists():
        print(f"오류: 세션 디렉토리가 없습니다: {session_path}", file=sys.stderr)
        sys.exit(1)

    session = Session(session_path)

    global _frame_iter
    _frame_iter = FrameIterator(session, fps=args.fps, loop=not args.once)

    server = HTTPServer(("0.0.0.0", args.port), MjpegHandler)
    print(f"""
╔══════════════════════════════════════════════╗
║       NavBlind Mock Stream Server            ║
╠══════════════════════════════════════════════╣
║  스트림 URL (에뮬레이터): http://10.0.2.2:{args.port}/stream  (백엔드: 8080)
║  스트림 URL (실제 기기):  http://<PC_IP>:{args.port}/stream
║  프레임 수: {session.frame_count()}
║  FPS: {"원본 타이밍" if args.fps == 0 else args.fps}
║  루프: {"끄기 (--once)" if args.once else "켜기"}
╚══════════════════════════════════════════════╝

Ctrl+C 로 종료
""")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n서버 종료")


if __name__ == "__main__":
    main()
