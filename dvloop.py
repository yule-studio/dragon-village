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

import argparse
import subprocess
import sys
import time
from datetime import datetime, timedelta

import dvguard

PKG = "com.highbrow.game"
SHOTS = "/Users/masterway/dragonvillage/screens"
LOG = "/Users/masterway/dragonvillage/loop_log.txt"

# --- 좌표 ---
T_LEVELUP = (1416, 930)     # 레벨 업 버튼
T_ITEM_OK = (997, 786)      # 아이템 사용 확인
T_RESULT_OK = (1170, 827)   # LEVEL UP! 결과 확인
T_DLG_X = (2028, 56)        # 레벨업 다이얼로그 닫기
T_MENU = (1977, 88)
T_SETTINGS = (1974, 641)
T_SAVE_OK = (1170, 663)     # 저장완료 확인
T_SET_X = (1647, 87)        # 설정창 닫기
T_PANEL = (368, 977)        # 하단 용 카드
T_NOTICE_CLOSE = (1170, 1013)

# 동굴 (즐겨찾기 해제용)
T_CAVE = (1714, 88)          # 상단 '동굴'
T_CAVE_SLOT1 = (374, 246)    # 동굴 좌측 목록 1번 (즐겨찾기 대상이 여기 온다)
T_STAR = (1732, 936)         # 즐겨찾기 별
T_CAVE_X = (2012, 53)        # 동굴 닫기

# 용 목록 슬롯 (스크롤 없이 보이는 5칸). 6번째 이후는 미지원
DRAGON_SLOTS = {0: None,  # 0 = 동굴에서 이미 선택된 용을 그대로 쓴다 (슬롯 탭 생략)
                1: (1076, 164), 2: (1076, 345), 3: (1076, 523),
                4: (1076, 702), 5: (1076, 883)}

# 아이템 목록 슬롯 (스크롤 없이 보이는 3칸)
ITEM_SLOTS = {1: (1305, 193), 2: (1305, 486), 3: (1305, 772)}

# 실행 인자로 채워진다
T_DRAGON = DRAGON_SLOTS[2]  # 기본: 번개고룡
T_ITEM = ITEM_SLOTS[2]      # 기본: 각성 Lv+1
STOP_AT = None              # datetime — 이 시각이 지나면 사이클 경계에서 정지

# 로그인
T_START = (1170, 540)
T_HIGHBROW = (1170, 759)
T_ID = (1170, 461)
T_PW = (1170, 339)
T_LOGIN = (1170, 699)

# MAX 뱃지 판정 지점 (밝으면 MAX, 어두우면 실패)
BADGES = {"HP": 441, "ATK": 532, "DEF": 624}
BADGE_XS = (1670, 1681, 1692)
T_GRADE = (696, 632)   # 결과창 등급 육각형


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


def is_title(f=None):
    """타이틀 화면(TOUCH TO START) 판정.

    '이전 드빌아이디' 버튼은 넓고 균일한 진한 파랑이다.
    월드맵의 에스텔라 지역도 그 자리가 파랗지만 그림이라 색이 고르지 않다.
    → 색상 조건 + 균일도를 함께 본다.
    """
    f = f or frame()
    pts = [px(f, x, 985) for x in (1750, 1790, 1900, 1940)]
    for r, g, b in pts:
        if not (b > 100 and b > r + 50 and r < 100):
            return False
    for ch in range(3):
        vals = [p[ch] for p in pts]
        if max(vals) - min(vals) > 14:
            return False
    return True


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


def _is_orange(rgb):
    r, g, b = rgb
    return r > 200 and b < 130 and (r - b) > 110


def badge_max(f, y):
    """MAX 뱃지 판정.

    밝기로 보면 안 된다 — 뱃지가 없을 때 그 자리에 무엇이 오는지가
    드래곤마다 다르다(스탯 숫자가 크면 어두운 박스, 작으면 밝은 베이지 배경).
    실제로 그 때문에 루아셰아 초반 판정이 전부 오탐이었다.

    뱃지는 주황 폭죽이고, 위아래 끝(dy=±24)이 가장 진한 주황이다.
    가운데는 흰 'MAX' 글자라 색으로 판정할 수 없다.
    실측: 뱃지 없음 0/6, 있음 6/6 으로 완전히 갈린다.
    """
    pts = [px(f, 1681 + dx, y + dy) for dy in (-24, 24) for dx in (-15, 0, 15)]
    return sum(_is_orange(p) for p in pts) >= 4


def grade_is_70(f):
    """레벨업 결과창의 등급 육각형이 7.0 인지.

    실측: 7.0 = 시안 (8,219,255) / 7.0 미만 = 금색 (255,202,49).
    가운데는 흰 숫자라 여러 점을 보고 다수결로 판정한다.
    True = 7.0, False = 7.0 미만, None = 판정 불가.

    스탯 뱃지와 달리 이 값은 '실제 조건' 그 자체다. 최종 판단 기준으로 쓴다.
    """
    gx, gy = T_GRADE
    blue = gold = 0
    for dx, dy in ((-14, 0), (14, 0), (0, -14), (0, 14), (0, 0)):
        r, g, b = px(f, gx + dx, gy + dy)
        if b > r + 40 and b > 120:
            blue += 1
        elif r > 180 and r > b + 60:
            gold += 1
    if blue >= 2 and gold == 0:
        return True
    if gold >= 2 and blue == 0:
        return False
    return None


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
    """월드맵 → 용 패널 → 대상 용 → 아이템 → 레벨업 다이얼로그

    용 패널은 토글이라 이미 열려 있으면 첫 탭이 닫는 데 쓰인다.
    화면 상태를 알 수 없으므로 다이얼로그 확인을 게이트로 최대 3회 재시도한다.
    """
    if is_title():
        log("    타이틀 화면 감지 — 로그인부터 진행")
        login()
    for attempt in range(1, 4):
        if is_title():
            abort("로그아웃 상태로 되돌아갔다 — 탭하지 않고 중단", "logged_out")
        tap(T_PANEL); time.sleep(4)
        if T_DRAGON is not None:
            tap(T_DRAGON); time.sleep(3)
        tap(T_ITEM); time.sleep(3)
        if wait_for(is_levelup_dialog, timeout=12):
            return
        log(f"    다이얼로그 미확인 ({attempt}/3) — 재시도")
    abort("레벨업 다이얼로그가 안 열림 (3회 시도)", "no_dialog")


def is_favorited(f=None):
    """즐겨찾기 별이 켜져 있으면 True (주황), 꺼져 있으면 False (회색).
    어느 쪽도 확실하지 않으면 None — 이 경우 아무것도 누르지 않는다."""
    f = f or frame()
    r, g, b = px(f, *T_STAR)
    if r > 190 and g > 110 and b < 110 and r > b + 90:
        return True
    if abs(r - g) < 30 and abs(g - b) < 30 and 60 < r < 190:
        return False
    return None


def unfavorite():
    """목표 달성 후 즐겨찾기를 끄고 저장한다.

    별 색이 '켜짐'으로 확실히 판정될 때만 누른다. 애매하면 손대지 않고 보고한다.
    """
    log("  즐겨찾기 해제 시도")
    tap(T_CAVE); time.sleep(5)
    tap(T_CAVE_SLOT1); time.sleep(3)

    st = is_favorited()
    log(f"    별 픽셀 {px(frame(), *T_STAR)} → {'켜짐' if st else ('꺼짐' if st is False else '판정불가')}")
    if st is not True:
        log("    확실하지 않아 건드리지 않는다. 수동 확인 필요.")
        shot("FAVORITE_manual_check")
        tap(T_CAVE_X); time.sleep(4)
        return False

    tap(T_STAR); time.sleep(3)
    if is_favorited() is not False:
        log("    해제 확인 실패. 수동 확인 필요.")
        shot("FAVORITE_off_failed")
        tap(T_CAVE_X); time.sleep(4)
        return False

    log("    즐겨찾기 해제됨")
    tap(T_CAVE_X); time.sleep(4)

    # 해제 상태를 서버에 남긴다
    for attempt in range(1, 4):
        tap(T_MENU); time.sleep(2)
        tap(T_SETTINGS); time.sleep(3)
        ok, _, _ = dvguard.is_settings_open()
        if ok:
            break
    else:
        log("    해제 후 저장 실패 — 설정창 미확인")
        return False
    x, y = dvguard.SAVE_SAFE_TAP
    tap((x, y)); time.sleep(3)
    tap(T_SAVE_OK); time.sleep(2)
    tap(T_SET_X); time.sleep(2)
    tap(T_MENU); time.sleep(2)
    log("    해제 후 저장 완료")
    return True


def save():
    """레벨업 다이얼로그 → 메뉴 → 설정 → 저장 → 다이얼로그 복귀

    메뉴 토글은 직전 화면 상태(용 패널 열림 / 맵 오버레이 떠 있음)에 따라
    한 번 더 눌러야 할 때가 있다. 눈으로 확인할 수 없으므로 이미 검증된
    is_settings_open() 을 게이트로 삼아 최대 3회 재시도한다.
    """
    log("  저장 진행")

    tap(T_DLG_X); time.sleep(2)                 # 레벨업 다이얼로그 닫기
    if is_levelup_dialog():                     # 안 닫혔으면 한 번 더
        tap(T_DLG_X); time.sleep(2)

    ok = False
    for attempt in range(1, 4):
        tap(T_MENU); time.sleep(2)
        tap(T_SETTINGS); time.sleep(3)
        ok, s, d = dvguard.is_settings_open()
        if ok:
            break
        log(f"    설정창 미확인 ({attempt}/3) 저장={s} 삭제={d} — 재시도")
    if not ok:
        abort(f"설정창 검증 3회 실패 저장={s} 삭제={d}", "settings")

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
    triple = all(res.values())
    grade = grade_is_70(f)
    win = triple and grade is True
    mark = " ".join(f"{k}{'O' if v else 'X'}" for k, v in res.items())
    gtxt = {True: "7.0", False: "7.0미만", None: "등급판정불가"}[grade]
    log(f"사이클 {n}: {mark} / {gtxt} → {'★트리플맥스★' if win else '실패'}")

    # 뱃지와 등급이 어긋나면 내 판정 모델이 틀린 것이다. 저장하지 말고 멈춘다.
    if triple and grade is not True:
        log(f"    !! 뱃지는 3개 MAX 인데 등급이 7.0 이 아니다 (육각형 {px(f, *T_GRADE)})")
        shot(f"MISMATCH_c{n}")
        abort("판정 불일치 — 사람이 확인해야 한다", f"mismatch_c{n}")

    if win:
        shot(f"SUCCESS_cycle{n:04d}")

    tap(T_RESULT_OK); time.sleep(3)
    return win


def parse_args():
    p = argparse.ArgumentParser(
        description="드래곤빌리지 데삭 루프",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    # 기존 위치 인자 호환: <최대사이클> [목표레벨수] [현재레벨]
    p.add_argument("max_cycles", nargs="?", type=int, default=10)
    p.add_argument("target", nargs="?", type=int, default=14,
                   help="올릴 레벨 수 (성공 횟수)")
    p.add_argument("base", nargs="?", type=int, default=31, help="현재 레벨")
    p.add_argument("--dragon-slot", type=int, default=2,
                   choices=sorted(DRAGON_SLOTS), help="용 목록 슬롯")
    p.add_argument("--item-slot", type=int, default=2,
                   choices=sorted(ITEM_SLOTS), help="아이템 목록 슬롯")
    p.add_argument("--name", default="번개고룡", help="로그 표시용 대상 이름")
    p.add_argument("--until", metavar="HH:MM",
                   help="이 시각이 지나면 사이클 경계에서 정지")
    return p.parse_args()


def resolve_stop_at(hhmm):
    if not hhmm:
        return None
    h, m = (int(x) for x in hhmm.split(":"))
    now = datetime.now()
    t = now.replace(hour=h, minute=m, second=0, microsecond=0)
    if t <= now:
        t += timedelta(days=1)
    return t


def main():
    global T_DRAGON, T_ITEM, STOP_AT
    a = parse_args()
    max_cycles, target, base = a.max_cycles, a.target, a.base
    T_DRAGON = DRAGON_SLOTS[a.dragon_slot]
    T_ITEM = ITEM_SLOTS[a.item_slot]
    STOP_AT = resolve_stop_at(a.until)

    log("=" * 60)
    log(f"루프 시작: {a.name} Lv.{base} → Lv.{base + target}")
    log(f"  용 슬롯 {a.dragon_slot}{T_DRAGON or ' (현재 선택된 용)'} / 아이템 슬롯 {a.item_slot}{T_ITEM}")
    log(f"  최대 {max_cycles}사이클" +
        (f" / {STOP_AT:%H:%M} 까지" if STOP_AT else ""))
    log("토큰: 이 루프는 Claude 개입 없이 자율 실행된다 → 사이클당 토큰 소모 0")
    log("      Claude 토큰은 감독/보고 턴에서만 발생. 정확한 수치는 /cost 로 확인.")

    if not is_levelup_dialog():
        log("레벨업 다이얼로그가 아님 → 진입 시도")
        open_dialog()

    wins = 0
    t0 = time.time()
    n = 0
    for n in range(1, max_cycles + 1):
        if STOP_AT and datetime.now() >= STOP_AT:
            log(f"지정 시각({STOP_AT:%H:%M}) 도달 — 정지")
            break
        if cycle(n):
            wins += 1
            log(f"  누적 성공 {wins}/{target}  (Lv.{base + wins})")
            if wins >= target:
                log(f"목표 달성. Lv.{base + wins}")
                save()
                unfavorite()
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
