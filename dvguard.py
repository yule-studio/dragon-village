#!/usr/bin/env python3
"""
드래곤빌리지 조작 가드.

목적: '계정삭제' 오탭을 물리적으로 불가능하게 만든다.
설정창에서 [저장][계정삭제][로그아웃] 이 나란히 붙어 있어 오탭 시 계정이 날아간다.

사용법:
    python3 dvguard.py tap X Y      # 일반 탭 (위험구역이면 거부)
    python3 dvguard.py save         # 저장 (화면 색상 검증 후에만 실행)
    python3 dvguard.py check        # 현재 설정창 여부만 확인

이 스크립트를 우회해서 `adb shell input tap` 을 직접 쓰지 않는다.
"""

import subprocess
import struct
import sys

# --- 설정창 좌표 (2340x1080 가로 기준, 2026-08-01 실측) ---
SAVE_CENTER = (896, 890)     # 저장 (초록)
DELETE_CENTER = (1166, 890)  # 계정삭제 (빨강)  ← 절대 금지
LOGOUT_CENTER = (1437, 890)  # 로그아웃 (보라)

# 저장 버튼 안에서 계정삭제로부터 최대한 먼 지점을 찍는다
SAVE_SAFE_TAP = (842, 890)

# 절대 금지 구역: 계정삭제 버튼 + 여유 마진
DANGER = {"x1": 1030, "y1": 845, "x2": 1300, "y2": 935}


def screencap():
    """원시 프레임버퍼를 받아 (width, height, pixels) 반환."""
    raw = subprocess.run(
        ["adb", "exec-out", "screencap"], capture_output=True, check=True
    ).stdout
    w, h = struct.unpack("<II", raw[:8])
    # Android 13+ 는 colorSpace 필드가 붙어 헤더가 16바이트
    for header in (16, 12):
        if len(raw) - header == w * h * 4:
            return w, h, raw[header:]
    raise RuntimeError(f"프레임버퍼 파싱 실패 (len={len(raw)}, {w}x{h})")


def pixel(w, pixels, x, y):
    off = (y * w + x) * 4
    return pixels[off], pixels[off + 1], pixels[off + 2]


def is_settings_open():
    """저장=초록, 계정삭제=빨강 이 둘 다 맞을 때만 설정창으로 인정."""
    w, h, px = screencap()
    sr, sg, sb = pixel(w, px, *SAVE_CENTER)
    dr, dg, db = pixel(w, px, *DELETE_CENTER)
    green_ok = sg > 100 and sg > sr + 30 and sg > sb + 30
    red_ok = dr > 140 and dr > dg + 60 and dr > db + 60
    return green_ok and red_ok, (sr, sg, sb), (dr, dg, db)


def in_danger(x, y):
    return DANGER["x1"] <= x <= DANGER["x2"] and DANGER["y1"] <= y <= DANGER["y2"]


def do_tap(x, y):
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    cmd = sys.argv[1]

    if cmd == "tap":
        x, y = int(sys.argv[2]), int(sys.argv[3])
        if in_danger(x, y):
            print(f"거부: ({x},{y}) 는 계정삭제 금지구역이다. 탭하지 않았다.")
            return 1
        do_tap(x, y)
        print(f"탭 ({x},{y})")
        return 0

    if cmd == "check":
        ok, s, d = is_settings_open()
        print(f"설정창: {'열림' if ok else '아님'}  저장픽셀={s} 삭제픽셀={d}")
        return 0 if ok else 1

    if cmd == "save":
        ok, s, d = is_settings_open()
        if not ok:
            print(f"거부: 설정창이 확인되지 않는다. 저장픽셀={s} 삭제픽셀={d}")
            print("설정창을 먼저 연 뒤 다시 실행한다.")
            return 1
        x, y = SAVE_SAFE_TAP
        if in_danger(x, y):
            print("거부: 저장 좌표가 금지구역과 겹친다. 좌표 재확인 필요.")
            return 1
        do_tap(x, y)
        print(f"저장 탭 완료 ({x},{y}) — 설정창 검증 통과")
        return 0

    print(f"알 수 없는 명령: {cmd}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
