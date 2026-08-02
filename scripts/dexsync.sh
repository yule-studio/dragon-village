#!/bin/bash
# 도감 증분 동기화. 신규가 있을 때만 실제 수집을 돈다.
# launchd 가 주기적으로 호출한다 (scripts/install-scheduler.sh 로 등록).
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG="$REPO/data/dexsync.log"
mkdir -p "$REPO/data"
cd "$REPO" || exit 1

say() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOG"; }

say "동기화 확인 시작"

if ! out=$(python3 dexcrawl.py --check 2>&1); then
  # 종료코드 1 = 신규 있음
  say "$out"
  say "신규 감지 → 수집 시작"
  python3 dexcrawl.py --sync >> "$LOG" 2>&1
  python3 dexcrawl.py --images --steps 0 >> "$LOG" 2>&1
  say "수집 완료"
else
  say "$out — 갱신 없음"
fi

# 로그가 무한정 커지지 않게 최근 2000줄만 유지
if [ -f "$LOG" ]; then
  tail -n 2000 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi
