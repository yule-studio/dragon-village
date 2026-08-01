#!/usr/bin/env python3
"""
번개고룡 데삭 루프.

레벨업 1회 → 트리플맥스 판정 → 성공이면 저장, 실패면 데삭+재로그인 → 반복.

    python3 dvloop.py <최대사이클> [목표성공횟수]

안전장치:
  - 모든 탭은 dvguard 경유 (계정삭제 금지구역 차단)
  - 저장은 dvguard.save 의 픽셀 검증 통과 시에만
  - 예상과 다른 화면이면 즉시 중단하고 스크린샷을 남긴다 (임의로 탭하지 않는다)
"""

import subprocess
import sys
import time
from datetime import datetime

import dvguard

PKG = "com.highbrow.game"
SHOTS = "/Users/masterway/dragonvillage/screens"
LOG = "/Users/masterway/dragonvillage/loop_log.txt"

# --- 좌표 ---
T_ITEM = (1305, 486)        # 각성 Lv+1 아이템
T_LEVELUP = (1416, 930)     # 레벨 업 버튼
T_ITEM_OK = (997, 786)      # 아이템 사용 확인
T_RESULT_OK = (1170, 827)   # LEVEL UP! 결과 확인
T_DLG_X = (2028, 56)        # 레벨업 다이얼로그 닫기
T_MENU = (1977, 88)
T_SETTINGS = (1974, 641)
T_SAVE_OK = (1170, 663)     # 저장완료 확인
T_SET_X = (1647, 87)        # 설정창 닫기
T_PANEL = (368, 977)        # 하단 용 카드
T_DRAGON = (1076, 345)      # 번개고룡 슬롯
T_NOTICE_CLOSE = (1170, 1013)

# 로그인
T_START = (1170, 540)
T_HIGHBROW = (1170, 759)
T_ID = (1170, 461)
T_PW = (1170, 339)
T_LOGIN = (1170, 699)

# MAX 뱃지 판정 지점 (밝으면 MAX, 어두우면 실패)
BADGES = {"HP": 441, "ATK": 532, "DEF": 624}
BADGE_XS = (1670, 1681, 1692)


def log(msg):
    line = f"[{datetime.now():%H:%M:%S}] {msg}"
    print(line, flush=True)
    with open(LOG, "a") as f:
        f.write(line + "\n")


def tap(xy):
    x, y = xy
    if dvguard.in_danger(x, y):
        raise RuntimeError(f"금지구역 탭 시도 ({x},{y}) — 중단")
    dvguard.do_tap(x, y)


def frame():
    return dvguard.screencap()


def px(f, x, y):
    w, _, pixels = f
    return dvguard.pixel(w, pixels, x, y)


def is_levelup_dialog(f=None):
    f = f or frame()
    r, g, b = px(f, 1350, 915)
    return r > 230 and 150 < g < 210 and b < 60


def is_result_popup(f=None):
    f = f or frame()
    r, g, b = px(f, *T_RESULT_OK)
    return b > r + 40 and b > 90


def is_notice(f=None):
    f = f or frame()
    a = sum(px(f, 600, 1013))
    b = sum(px(f, 1800, 1013))
    return a < 260 and b < 260


def is_login_select(f=None):
    """구글/페이스북/하이브로 3버튼 선택 화면"""
    f = f or frame()
    gr, gg, gb = px(f, 470, 386)      # 구글 = 붉은 계열
    hr, hg, hb = px(f, 470, 759)      # 하이브로 = 회색
    google = gr > 150 and gr > gg + 15
    highbrow = abs(hr - hg) < 30 and abs(hg - hb) < 30 and 70 < hr < 210
    return google and highbrow


def is_id_form(f=None):
    """하이브로 아이디/비밀번호 입력 폼 (흰 입력칸 2개)"""
    f = f or frame()
    return all(min(px(f, 1170, y)) > 215 for y in (461, 580))


def badge_max(f, y):
    tot = sum(sum(px(f, x, y)) for x in BADGE_XS)
    return tot / len(BADGE_XS) > 400


def wait_for(pred, timeout=25, interval=1.0):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(interval)
    return False


def shot(name):
    path = f"{SHOTS}/{name}.png"
    with open(path, "wb") as f:
        f.write(subprocess.run(
            ["adb", "exec-out", "screencap", "-p"],
            capture_output=True, check=True).stdout)
    return path


def abort(msg, tag):
    p = shot(f"ABORT_{tag}")
    log(f"!! 중단: {msg}")
    log(f"!! 스크린샷: {p}")
    sys.exit(1)


# ---------------- 로그인 / 데삭 ----------------

def password():
    return subprocess.run(
        ["security", "find-generic-password",
         "-s", "dragonvillage-highbrow", "-a", "seo4766", "-w"],
        capture_output=True, text=True, check=True).stdout.strip()


def login():
    """반드시 하이브로 계정으로만 로그인한다. 확인 안 되면 중단."""
    log("  로그인 시작")
    tap(T_START); time.sleep(5)

    # ★ 구글로 새는 사고 방지 — 3버튼 선택 화면을 확인해야만 진행
    if not wait_for(is_login_select, timeout=20):
        abort("로그인 선택 화면이 아님 (구글 자동로그인 의심). 하이브로 확인 불가", "no_login_select")

    tap(T_HIGHBROW); time.sleep(3)

    # ★ 하이브로 입력폼이 떠야만 자격증명을 입력한다
    if not wait_for(is_id_form, timeout=15):
        abort("하이브로 아이디 입력폼이 안 뜸 — 잘못된 로그인 경로", "no_id_form")

    tap(T_ID); time.sleep(2)
    subprocess.run(["adb", "shell", "input", "text", "'seo4766'"], check=True)
    time.sleep(1)
    tap(T_PW); time.sleep(2)
    subprocess.run(["adb", "shell", "input", "text", f"'{password()}'"], check=True)
    time.sleep(1)
    subprocess.run(["adb", "shell", "input", "keyevent", "KEYCODE_BACK"], check=True)
    time.sleep(2)
    tap(T_LOGIN); time.sleep(8)

    if not wait_for(is_notice, timeout=25):
        abort("로그인 후 공지창이 안 뜸", "login_no_notice")

    # 공지/출석 등 팝업이 겹쳐 뜨는 경우 최대 3장까지 닫는다
    for _ in range(3):
        if not is_notice():
            break
        tap(T_NOTICE_CLOSE); time.sleep(6)

    log("  로그인 완료 (하이브로 확인됨)")


def desak():
    log("  데삭 실행")
    out = subprocess.run(["adb", "shell", "pm", "clear", PKG],
                         capture_output=True, text=True, check=True).stdout
    if "Success" not in out:
        abort(f"pm clear 실패: {out.strip()}", "pmclear")
    subprocess.run(["adb", "shell", "monkey", "-p", PKG,
                    "-c", "android.intent.category.LAUNCHER", "1"],
                   capture_output=True, check=True)
    time.sleep(22)
    login()


def open_dialog():
    """월드맵 → 용 패널 → 번개고룡 → 아이템 → 레벨업 다이얼로그"""
    tap(T_PANEL); time.sleep(4)
    tap(T_DRAGON); time.sleep(3)
    tap(T_ITEM); time.sleep(3)
    if not wait_for(is_levelup_dialog, timeout=15):
        abort("레벨업 다이얼로그가 안 열림", "no_dialog")


def save():
    """레벨업 다이얼로그 → 메뉴 → 설정 → 저장 → 다이얼로그 복귀"""
    log("  저장 진행")
    tap(T_DLG_X); time.sleep(2)      # 다이얼로그 닫기 → 용 패널
    tap(T_MENU); time.sleep(2)       # 용 패널 닫기
    tap(T_MENU); time.sleep(2)       # 메뉴 열기
    tap(T_SETTINGS); time.sleep(3)

    ok, s, d = dvguard.is_settings_open()
    if not ok:
        abort(f"설정창 검증 실패 저장={s} 삭제={d}", "settings")

    x, y = dvguard.SAVE_SAFE_TAP
    tap((x, y)); time.sleep(3)
    tap(T_SAVE_OK); time.sleep(2)
    tap(T_SET_X); time.sleep(2)      # 설정 닫기 (메뉴는 열린 채)
    tap(T_MENU); time.sleep(2)       # 메뉴 닫기
    log("  저장 완료")
    open_dialog()


# ---------------- 메인 루프 ----------------

def cycle(n):
    """레벨업 1회. True=트리플맥스"""
    if not is_levelup_dialog():
        abort("사이클 시작 시 레벨업 다이얼로그가 아님", f"c{n}_start")

    tap(T_LEVELUP); time.sleep(3)
    tap(T_ITEM_OK); time.sleep(4)

    if not wait_for(is_result_popup, timeout=20):
        abort("레벨업 결과창이 안 뜸", f"c{n}_no_result")

    f = frame()
    res = {k: badge_max(f, y) for k, y in BADGES.items()}
    win = all(res.values())
    mark = " ".join(f"{k}{'O' if v else 'X'}" for k, v in res.items())
    log(f"사이클 {n}: {mark} → {'★트리플맥스★' if win else '실패'}")

    if win:
        shot(f"SUCCESS_cycle{n:04d}")

    tap(T_RESULT_OK); time.sleep(3)
    return win


def main():
    max_cycles = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    target = int(sys.argv[2]) if len(sys.argv) > 2 else 14
    base = int(sys.argv[3]) if len(sys.argv) > 3 else 31   # 현재 레벨

    log("=" * 60)
    log(f"루프 시작: 최대 {max_cycles}사이클 / 목표 {target}레벨 상승")
    log("토큰: 이 루프는 Claude 개입 없이 자율 실행된다 → 사이클당 토큰 소모 0")
    log("      Claude 토큰은 감독/보고 턴에서만 발생. 정확한 수치는 /cost 로 확인.")

    if not is_levelup_dialog():
        log("레벨업 다이얼로그가 아님 → 진입 시도")
        open_dialog()

    wins = 0
    t0 = time.time()
    for n in range(1, max_cycles + 1):
        if cycle(n):
            wins += 1
            log(f"  누적 성공 {wins}/{target}  (Lv.{base + wins})")
            if wins >= target:
                log(f"목표 달성. Lv.{base + wins}")
                save()
                break
            save()
        else:
            desak()
            open_dialog()

        el = time.time() - t0
        log(f"  경과 {el/60:.1f}분 / 사이클당 평균 {el/n:.0f}초")

    log(f"종료: {n}사이클, 성공 {wins}회, 총 {(time.time()-t0)/60:.1f}분")


if __name__ == "__main__":
    main()
