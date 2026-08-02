#!/usr/bin/env python3
"""
드빌 공식 도감 크롤러 → SQLite.

    python3 dexcrawl.py --sync            메타데이터 동기화 (신규만)
    python3 dexcrawl.py --images          썸네일 이미지 수집 (재개 가능)
    python3 dexcrawl.py --images --marks  원본 이미지까지
    python3 dexcrawl.py --status          적재 현황 / 갱신 필요 여부
    python3 dexcrawl.py --check           원격 totalCount 만 확인 (스케줄러용)

증분 갱신: meta.total_count 와 비교해 늘어난 만큼만 가져온다.
이미지는 이미 받은 것을 건너뛰므로 중단 후 다시 실행하면 이어서 받는다.
"""

import argparse
import json
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

API = "https://www.dragonvillage.net/info/dragon/list"
CDN = "https://cdn.highbrow-inc.com/card/dv1"
DB = "data/dragondex.db"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
PAGE_SIZE = 100
DELAY = 0.4          # 요청 간 지연 — 사이트에 부담을 주지 않는다

STAT_COLS = [f"{p}{m}{s}" for p in ("add", "init") for m in ("Min", "Max")
             for s in ("Hp", "At", "Df")] + \
            [f"lv{m}{s}Max" for m in ("Min", "Max") for s in ("Hp", "At", "Df")]

SCHEMA = f"""
CREATE TABLE IF NOT EXISTS dragon (
  dragon_no   INTEGER PRIMARY KEY,
  name        TEXT NOT NULL,
  attr        TEXT,
  type        TEXT,
  gen         TEXT,
  comment     TEXT,
  {", ".join(f"{c} INTEGER" for c in STAT_COLS)},
  fetched_at  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_dragon_name ON dragon(name);
CREATE INDEX IF NOT EXISTS idx_dragon_attr ON dragon(attr);

CREATE TABLE IF NOT EXISTS dragon_image (
  dragon_no  INTEGER NOT NULL,
  kind       TEXT NOT NULL,          -- thumb | mark
  step       INTEGER NOT NULL,       -- 0~7 (진화 단계)
  img_file   TEXT NOT NULL,
  bytes      INTEGER,
  data       BLOB,
  fetched_at TEXT,
  PRIMARY KEY (dragon_no, kind, step)
);

CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT
);
"""


def now():
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def db():
    import os
    os.makedirs(os.path.dirname(DB), exist_ok=True)
    c = sqlite3.connect(DB)
    c.executescript(SCHEMA)
    return c


def meta_get(c, k, default=None):
    r = c.execute("SELECT value FROM meta WHERE key=?", (k,)).fetchone()
    return r[0] if r else default


def meta_set(c, k, v):
    c.execute("INSERT INTO meta(key,value) VALUES(?,?) "
              "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (k, str(v)))


def fetch_page(page, limit=PAGE_SIZE):
    body = json.dumps({"page": page, "limit": limit, "searchAttr": "",
                       "searchType": "", "search": ""}).encode()
    req = urllib.request.Request(API, data=body, method="POST", headers={
        "Content-Type": "application/json", "User-Agent": UA,
        "Referer": "https://www.dragonvillage.net/info/dragon"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def remote_total():
    return fetch_page(1, 1)["totalCount"]


# ---------------- 메타데이터 ----------------

def sync(force=False):
    c = db()
    total = remote_total()
    known = int(meta_get(c, "total_count", 0))
    have = c.execute("SELECT COUNT(*) FROM dragon").fetchone()[0]

    print(f"원격 {total}건 / 로컬 {have}건 (직전 기록 {known})")
    if not force and have >= total:
        print("갱신할 내용이 없다.")
        c.close()
        return 0

    pages = (total + PAGE_SIZE - 1) // PAGE_SIZE
    added = updated = 0
    for p in range(1, pages + 1):
        data = fetch_page(p)
        for v in data["list"]:
            no = v["dragonNo"]
            exists = c.execute("SELECT 1 FROM dragon WHERE dragon_no=?", (no,)).fetchone()
            if exists and not force:
                continue
            cols = ["dragon_no", "name", "attr", "type", "gen", "comment"] + STAT_COLS + ["fetched_at"]
            vals = [no, v["dragonName"], v.get("attr"), v.get("type"), v.get("gen"),
                    v.get("comment")] + [v.get(k) for k in STAT_COLS] + [now()]
            c.execute(f"INSERT INTO dragon({','.join(cols)}) "
                      f"VALUES({','.join('?' * len(cols))}) "
                      f"ON CONFLICT(dragon_no) DO UPDATE SET "
                      f"{','.join(f'{x}=excluded.{x}' for x in cols[1:])}", vals)
            updated += 1 if exists else 0
            added += 0 if exists else 1

            # 이미지 목록은 메타만 먼저 기록해두고 실제 다운로드는 --images 에서
            for kind, key in (("thumb", "dragonThumbImgFileList"),
                              ("mark", "dragonMarkImgFileList")):
                for img in v.get(key) or []:
                    f = img.get("imgFile") or ""
                    if not f:
                        continue
                    step = int(str(img.get("key", "_0")).rsplit("_", 1)[-1] or 0)
                    c.execute("INSERT OR IGNORE INTO dragon_image"
                              "(dragon_no,kind,step,img_file) VALUES(?,?,?,?)",
                              (no, kind, step, f))
        c.commit()
        print(f"  페이지 {p}/{pages}  신규 {added} · 갱신 {updated}")
        time.sleep(DELAY)

    meta_set(c, "total_count", total)
    meta_set(c, "last_sync", now())
    c.commit()
    print(f"완료 — 신규 {added}건, 갱신 {updated}건")
    c.close()
    return added


# ---------------- 이미지 ----------------

def fetch_images(kinds=("thumb",), limit=None, steps=None):
    c = db()
    args = list(kinds)
    q = ("SELECT dragon_no,kind,step,img_file FROM dragon_image "
         f"WHERE data IS NULL AND kind IN ({','.join('?' * len(kinds))})")
    if steps:
        q += f" AND step IN ({','.join('?' * len(steps))})"
        args += list(steps)
    q += " ORDER BY dragon_no, kind, step"
    rows = c.execute(q, args).fetchall()
    if limit:
        rows = rows[:limit]
    print(f"받을 이미지 {len(rows)}장")

    ok = fail = 0
    for i, (no, kind, step, f) in enumerate(rows, 1):
        url = f"{CDN}/dv1_{kind}/{f}"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                blob = r.read()
            c.execute("UPDATE dragon_image SET data=?,bytes=?,fetched_at=? "
                      "WHERE dragon_no=? AND kind=? AND step=?",
                      (blob, len(blob), now(), no, kind, step))
            ok += 1
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
            fail += 1
            print(f"  실패 #{no} {kind}_{step} {f}: {e}")
        if i % 50 == 0:
            c.commit()
            print(f"  {i}/{len(rows)}  성공 {ok} · 실패 {fail}")
        time.sleep(DELAY / 4)
    c.commit()
    print(f"완료 — 성공 {ok}, 실패 {fail}")
    c.close()


# ---------------- 상태 ----------------

def status():
    c = db()
    total = c.execute("SELECT COUNT(*) FROM dragon").fetchone()[0]
    imgs = c.execute("SELECT kind, COUNT(*), SUM(data IS NOT NULL), "
                     "COALESCE(SUM(bytes),0) FROM dragon_image GROUP BY kind").fetchall()
    print(f"드래곤        {total}건")
    print(f"마지막 동기화  {meta_get(c, 'last_sync', '-')}")
    print(f"기록된 총계    {meta_get(c, 'total_count', '-')}")
    for kind, n, got, size in imgs:
        print(f"이미지 {kind:5s}  {got or 0}/{n}장  {(size or 0)/1e6:.1f}MB")
    try:
        rt = remote_total()
        print(f"원격 총계      {rt}  →  {'갱신 필요' if rt > total else '최신'}")
    except Exception as e:
        print(f"원격 확인 실패: {e}")
    c.close()


def check():
    """스케줄러용 — 신규가 있으면 종료코드 1"""
    c = db()
    have = c.execute("SELECT COUNT(*) FROM dragon").fetchone()[0]
    c.close()
    rt = remote_total()
    new = rt - have
    print(f"원격 {rt} / 로컬 {have} → 신규 {max(0, new)}건")
    return 1 if new > 0 else 0


def main():
    p = argparse.ArgumentParser(description="드빌 도감 크롤러")
    p.add_argument("--sync", action="store_true", help="메타데이터 동기화")
    p.add_argument("--force", action="store_true", help="기존 건도 다시 받기")
    p.add_argument("--images", action="store_true", help="이미지 수집")
    p.add_argument("--marks", action="store_true", help="원본 이미지까지 (기본: 썸네일만)")
    p.add_argument("--limit", type=int, help="이미지 최대 개수")
    p.add_argument("--steps", help="받을 진화 단계 (예: 0 또는 0,1,2). 미지정=전부")
    p.add_argument("--status", action="store_true")
    p.add_argument("--check", action="store_true", help="신규 여부만 확인")
    a = p.parse_args()

    if a.check:
        return check()
    if a.sync:
        sync(force=a.force)
    if a.images:
        steps = [int(x) for x in a.steps.split(",")] if a.steps else None
        fetch_images(("thumb", "mark") if a.marks else ("thumb",), a.limit, steps)
    if a.status or not (a.sync or a.images):
        status()
    return 0


if __name__ == "__main__":
    sys.exit(main())
