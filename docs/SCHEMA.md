# 데이터 스키마

루프 엔진(현재 Python, 향후 Kotlin)과 UI 사이의 계약이다.
**엔진이 바뀌어도 이 스키마는 그대로 유지한다.**

파일 위치는 `config.json` 의 `paths` 를 따른다. 기본값:

```
data/
├── config.json      설정 (사람이 편집 / 앱에서 수정)
├── state.json       현재 상태 (엔진이 계속 덮어씀)
├── events.jsonl     이벤트 로그 (append only)
└── shots/           스크린샷
```

---

## 1. `config.json` — 설정

앱 설정 화면에서 수정하는 값. 엔진은 **매 사이클 시작 시 다시 읽는다**(재시작 없이 반영).

```jsonc
{
  "schemaVersion": 1,

  "target": {
    "dragonSlot": 2,              // 용 목록에서 몇 번째 (1-based)
    "dragonName": "번개고룡",       // 표시용. 판정에는 쓰지 않는다
    "awakened": true,             // 각성 여부 → 사용 아이템이 달라진다
    "itemSlot": 2,                // 아이템 목록에서 몇 번째
    "itemName": "각성 Lv+1",
    "startLevel": 32,
    "targetLevel": 45
  },

  "limits": {
    "maxCycles": 400,             // 0 = 무제한 (권장하지 않음)
    "maxMinutes": 0,              // 0 = 무제한
    "stopOnAbort": true           // 예상 밖 화면이면 즉시 중단
  },

  "account": {
    "provider": "highbrow",       // highbrow 고정. google/facebook 금지
    "id": "seo4766"
    // 비밀번호는 여기 두지 않는다. 안전 저장소에서만 읽는다.
  },

  "timing": {                     // 초 단위. 기기가 느리면 늘린다
    "afterLevelUp": 4,
    "afterDesakLaunch": 22,
    "afterLogin": 8,
    "afterNoticeClose": 6
  },

  "paths": {
    "data": "./data",
    "shots": "./data/shots"
  }
}
```

---

## 2. `state.json` — 현재 상태

엔진 상태가 바뀔 때마다 **통째로 덮어쓴다.** UI 는 이 파일만 폴링하면 화면 전체를 그릴 수 있다.

```jsonc
{
  "schemaVersion": 1,
  "running": true,
  "phase": "login",               // 아래 phase 표 참고
  "startedAt": "2026-08-02T00:12:34+09:00",
  "updatedAt": "2026-08-02T00:29:11+09:00",

  "progress": {
    "cycle": 29,                  // 지금까지 돈 사이클
    "wins": 1,                    // 트리플맥스 성공 횟수
    "level": 32,                  // 현재 레벨
    "targetLevel": 45,
    "remaining": 13
  },

  "stats": {
    "maxRate": { "HP": 0.172, "ATK": 0.655, "DEF": 0.828 },
    "tripleMaxRate": 0.034,
    "avgCycleSec": 78,
    "etaAt": "2026-08-02T06:40:00+09:00"   // 미정이면 null
  },

  "lastCycle": {
    "n": 29,
    "stats": { "HP": false, "ATK": true, "DEF": true },
    "win": false,
    "at": "2026-08-02T00:29:11+09:00"
  },

  "lastError": null               // 중단됐으면 abort 이벤트와 동일한 형태
}
```

### `phase` 값

| 값 | 의미 |
| --- | --- |
| `idle` | 대기 (실행 전 / 종료 후) |
| `entering` | 용 패널 → 대상 용 → 아이템 진입 중 |
| `levelup` | 아이템 사용 중 |
| `judge` | 결과 판정 중 |
| `save` | 메뉴 → 설정 → 저장 |
| `desak` | 데이터 삭제 진행 |
| `login` | 재로그인 |
| `done` | 목표 달성 |
| `aborted` | 예상 밖 화면으로 중단 |

---

## 3. `events.jsonl` — 이벤트 로그

**한 줄 = JSON 1개, append only.** 공통 필드는 `ts`, `type`.

```jsonc
{"ts":"2026-08-02T00:12:34+09:00","type":"run_start",
 "config":{"targetLevel":45,"startLevel":32,"maxCycles":400}}

{"ts":"2026-08-02T00:12:40+09:00","type":"phase","phase":"levelup"}

{"ts":"2026-08-02T00:29:11+09:00","type":"cycle","n":29,
 "stats":{"HP":false,"ATK":true,"DEF":true},"win":false,"durationSec":78}

{"ts":"2026-08-02T00:04:48+09:00","type":"level_up","level":32,"wins":1}

{"ts":"2026-08-02T00:05:07+09:00","type":"save","ok":true}

{"ts":"2026-08-02T00:12:59+09:00","type":"desak","ok":true}

{"ts":"2026-08-02T00:13:56+09:00","type":"login","provider":"highbrow","verified":true}

{"ts":"2026-08-02T00:06:52+09:00","type":"abort",
 "reason":"레벨업 다이얼로그가 안 열림","code":"NO_DIALOG",
 "shot":"data/shots/ABORT_no_dialog.png"}

{"ts":"2026-08-02T06:40:00+09:00","type":"run_end",
 "result":"done","cycles":312,"wins":13,"finalLevel":45}
```

### `abort.code` 목록

| code | 의미 |
| --- | --- |
| `NO_LOGIN_SELECT` | 로그인 선택 화면이 아님 (구글 자동로그인 의심) |
| `NO_ID_FORM` | 하이브로 입력폼이 안 뜸 |
| `NO_NOTICE` | 로그인 후 공지창이 안 뜸 |
| `NO_DIALOG` | 레벨업 다이얼로그가 안 열림 |
| `NO_RESULT` | 레벨업 결과창이 안 뜸 |
| `SETTINGS_UNVERIFIED` | 설정창 검증 실패 (저장 미실행) |
| `DESAK_FAILED` | 데이터 삭제 실패 |
| `DEVICE_LOST` | 기기 연결 끊김 |

---

## 4. 설계 원칙

- **`state.json` 은 UI 전용.** 엔진은 이 파일을 읽어서 판단하지 않는다(단일 진실 원천은 엔진 메모리).
- **`events.jsonl` 은 지우지 않는다.** 통계·사후 분석의 근거다.
- **비밀번호는 어떤 파일에도 쓰지 않는다.** macOS 키체인 / Android Keystore 에서만 읽는다.
- **`schemaVersion` 을 올릴 때는 UI 와 동시에 올린다.** 하위호환이 깨지는 변경은 PR 에 명시.
