#!/bin/bash
# 도감 동기화 스케줄러 등록/해제 (macOS launchd)
#
#   scripts/install-scheduler.sh install     매주 일요일 06:00 실행
#   scripts/install-scheduler.sh uninstall
#   scripts/install-scheduler.sh status
#   scripts/install-scheduler.sh run         즉시 1회 실행 (동작 확인용)
set -euo pipefail

LABEL="net.yulestudio.dragonvillage.dexsync"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

case "${1:-status}" in
install)
  mkdir -p "$HOME/Library/LaunchAgents" "$REPO/data"
  cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>$REPO/scripts/dexsync.sh</string>
  </array>
  <key>StartCalendarInterval</key>
  <dict>
    <key>Weekday</key><integer>0</integer>
    <key>Hour</key><integer>6</integer>
    <key>Minute</key><integer>0</integer>
  </dict>
  <!-- 실행 시각에 맥이 꺼져 있었으면 다음 부팅 후 한 번 따라잡는다 -->
  <key>RunAtLoad</key><false/>
  <key>StandardOutPath</key><string>$REPO/data/launchd.out.log</string>
  <key>StandardErrorPath</key><string>$REPO/data/launchd.err.log</string>
</dict>
</plist>
EOF
  launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
  launchctl bootstrap "gui/$UID" "$PLIST"
  echo "등록됨 — 매주 일요일 06:00"
  echo "  plist: $PLIST"
  ;;
uninstall)
  launchctl bootout "gui/$UID/$LABEL" 2>/dev/null || true
  rm -f "$PLIST"
  echo "해제됨"
  ;;
run)
  launchctl kickstart -p "gui/$UID/$LABEL"
  echo "즉시 실행 요청함 — data/dexsync.log 확인"
  ;;
status)
  if launchctl print "gui/$UID/$LABEL" >/dev/null 2>&1; then
    echo "등록 상태: 활성"
    launchctl print "gui/$UID/$LABEL" | grep -E "state|last exit code|runs" | sed 's/^/  /'
  else
    echo "등록 상태: 없음"
  fi
  [ -f "$REPO/data/dexsync.log" ] && { echo "--- 최근 로그 ---"; tail -5 "$REPO/data/dexsync.log"; }
  ;;
*)
  echo "사용법: $0 {install|uninstall|status|run}" >&2
  exit 2
  ;;
esac
