#!/usr/bin/env bash
# NavBlind 세션 데이터 수집 스크립트
# 실제 폰에서 최신 세션을 PC로 복사한다.
#
# 사용법:
#   bash tools/collect_session.sh              # 최신 세션 가져오기
#   bash tools/collect_session.sh --all        # 모든 세션 가져오기
#   bash tools/collect_session.sh --list       # 폰의 세션 목록 확인

set -euo pipefail

DEVICE_SESSION_ROOT="/sdcard/Android/data/com.navblind/files/sessions"
LOCAL_SESSION_ROOT="$(dirname "$0")/../sessions"
mkdir -p "$LOCAL_SESSION_ROOT"

# ── 도움말 ────────────────────────────────────────────────────────────────────

usage() {
    echo "사용법: bash tools/collect_session.sh [옵션]"
    echo ""
    echo "옵션:"
    echo "  (없음)    최신 세션 1개 가져오기"
    echo "  --all     모든 세션 가져오기"
    echo "  --list    폰의 세션 목록 확인"
    echo "  --help    이 도움말"
    echo ""
    echo "예시:"
    echo "  bash tools/collect_session.sh"
    echo "  bash tools/collect_session.sh --all"
    echo "  python tools/mock_stream.py sessions/\$(ls sessions | tail -1)"
}

# ── ADB 연결 확인 ─────────────────────────────────────────────────────────────

check_adb() {
    if ! command -v adb &>/dev/null; then
        echo "오류: adb 가 PATH에 없습니다. Android SDK platform-tools 를 설치하세요." >&2
        exit 1
    fi

    local devices
    devices=$(adb devices | grep -v "^List" | grep -v "^$" | wc -l)
    if [ "$devices" -eq 0 ]; then
        echo "오류: 연결된 Android 기기가 없습니다. USB 케이블 또는 Wi-Fi ADB 를 확인하세요." >&2
        exit 1
    fi
    echo "연결된 기기: $(adb devices | grep -v '^List' | grep -v '^$' | head -1)"
}

# ── 세션 목록 ─────────────────────────────────────────────────────────────────

list_sessions() {
    echo "폰의 세션 목록:"
    adb shell ls -1 "$DEVICE_SESSION_ROOT" 2>/dev/null | while read -r session; do
        local ts_sec=$(( session / 1000 ))
        local date_str
        date_str=$(date -d "@$ts_sec" "+%Y-%m-%d %H:%M:%S" 2>/dev/null || \
                   date -r "$ts_sec" "+%Y-%m-%d %H:%M:%S" 2>/dev/null || \
                   echo "$session")
        echo "  $session  ($date_str)"
    done
}

# ── 세션 복사 ─────────────────────────────────────────────────────────────────

pull_session() {
    local session_id="$1"
    local dest="$LOCAL_SESSION_ROOT/$session_id"

    if [ -d "$dest" ]; then
        echo "이미 존재: $dest (스킵)"
        return
    fi

    echo "복사 중: $session_id → sessions/$session_id"
    adb pull "$DEVICE_SESSION_ROOT/$session_id" "$LOCAL_SESSION_ROOT/" 2>&1 | tail -1

    # 결과 요약
    local frames
    frames=$(find "$dest/frames" -name "*.jpg" 2>/dev/null | wc -l)
    local gpx_exists
    gpx_exists=$( [ -f "$dest/route.gpx" ] && echo "있음" || echo "없음" )
    local mp4_exists
    mp4_exists=$( [ -f "$dest/session.mp4" ] && echo "있음 (ARCore VPS 재생 가능)" || echo "없음" )
    echo "  완료: 프레임 ${frames}개, GPX ${gpx_exists}, ARCore .mp4 ${mp4_exists}"
}

pull_latest() {
    local latest
    latest=$(adb shell ls -1 "$DEVICE_SESSION_ROOT" 2>/dev/null | sort -n | tail -1 | tr -d '\r')
    if [ -z "$latest" ]; then
        echo "오류: 폰에 세션이 없습니다. 앱을 실행해 데이터를 먼저 수집하세요." >&2
        exit 1
    fi
    pull_session "$latest"
    echo ""
    echo "다음 명령으로 MJPEG 프레임을 재생하세요:"
    echo "  python tools/mock_stream.py sessions/$latest"
    echo ""
    echo "GPX 파일 위치 (에뮬레이터 Extended Controls → Location에 임포트):"
    echo "  sessions/$latest/route.gpx"
    if [ -f "$LOCAL_SESSION_ROOT/$latest/session.mp4" ]; then
        echo ""
        echo "ARCore session.mp4 발견 → 에뮬레이터에서 VPS 재생 가능"
        echo "  앱이 자동으로 session.mp4를 감지하여 Playback 모드로 실행합니다."
    fi
}

pull_all() {
    local sessions
    sessions=$(adb shell ls -1 "$DEVICE_SESSION_ROOT" 2>/dev/null | tr -d '\r')
    if [ -z "$sessions" ]; then
        echo "오류: 폰에 세션이 없습니다." >&2
        exit 1
    fi
    echo "$sessions" | while read -r session; do
        pull_session "$session"
    done
    echo ""
    echo "전체 세션을 sessions/ 폴더에 저장했습니다."
}

# ── 진입점 ────────────────────────────────────────────────────────────────────

case "${1:-}" in
    --help|-h)
        usage
        ;;
    --list)
        check_adb
        list_sessions
        ;;
    --all)
        check_adb
        pull_all
        ;;
    "")
        check_adb
        pull_latest
        ;;
    *)
        echo "알 수 없는 옵션: $1" >&2
        usage
        exit 1
        ;;
esac
