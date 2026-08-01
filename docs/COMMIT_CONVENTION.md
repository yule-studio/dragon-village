# 커밋 메시지 컨벤션

한국어 + gitmoji + 3 섹션 템플릿.

## 형식

```
<gitmoji> <한 줄 요약>

변경 이유
- 왜 이 변경이 필요한지

주요 변경 사항
- 무엇이 바뀌었는지 (파일 / 함수 / 기능)

비고
- 알려진 한계 / TODO / 후속 작업
```

## 예시

```
:sparkles: MCP run-loop / stop-loop 도구 추가

변경 이유
- 터미널에서 python3 dvloop.py 를 직접 치지 않고 대화로 실행하기 위함

주요 변경 사항
- server/tools/run_loop.py: 목표 레벨·최대 사이클 인자로 루프 기동
- server/tools/stop_loop.py: PID 확인 후 안전 종료
- dvloop.py: SHOTS/LOG 를 상대경로로 전환

비고
- 실기기 3사이클 검증 완료 (평균 81초/사이클)
- 상태 조회 도구는 후속 이슈에서
```

## gitmoji

| 이모지 | 용도 |
| --- | --- |
| `:sparkles:` ✨ | 새 기능 |
| `:bug:` 🐛 | 버그 픽스 |
| `:wrench:` 🔧 | 설정 / 인프라 |
| `:recycle:` ♻️ | 리팩토링 |
| `:memo:` 📝 | 문서 |
| `:white_check_mark:` ✅ | 테스트 |
| `:lock:` 🔒 | 보안 / 안전장치 |
| `:art:` 🎨 | 스타일 / 포맷 |
| `:zap:` ⚡ | 성능 |
| `:rocket:` 🚀 | 배포 / 릴리스 |
| `:label:` 🏷️ | 타입 / 스키마 |
| `:tada:` 🎉 | 초기 / 마일스톤 |

## 규칙

- 한 줄 요약은 **50자 이내**, 본문은 72자 줄바꿈
- **한 commit = 한 논리적 변경.** 하나의 PR 안에서 최소 3 commit 으로 분리
- `git add .` 금지 — 관련 파일만 명시적으로 stage
- "WIP", "fix typo" 단독 commit 금지 (작업 중 amend 또는 정리 후 커밋)
- **Co-Authored-By 트레일러 금지**

## 분리 가이드

좋은 예 (한 PR 안):

1. `:wrench: 설정 파일 분리 (대상 용 / 목표 레벨)`
2. `:sparkles: MCP run-loop 도구 추가`
3. `:white_check_mark: 판정 로직 단위 테스트`
4. `:memo: docs 갱신 (README / BRANCH_STRATEGY)`

피할 예:

- 여러 도메인을 한 PR 에 섞기 → PR 자체를 분리한다
- 요청 범위 밖 리팩터링 · 포맷 변경을 끼워 넣기
