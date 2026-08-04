package net.yulestudio.dvloop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay

/**
 * 레벨업 루프. dvloop.py 를 그대로 옮긴 것이다.
 *
 *   레벨업 1회 → 트리플맥스 + 7.0 판정 → 성공이면 저장, 실패면 데삭 + 재로그인
 *
 * 안전 원칙(파이썬과 동일):
 *  - 예상과 다른 화면이면 아무것도 누르지 않고 멈춘다
 *  - 저장은 설정창 픽셀 검증을 통과했을 때만
 *  - 뱃지는 MAX 인데 등급이 7.0 이 아니면 판정 모델이 틀린 것이므로 멈춘다
 *  - 계정삭제 금지구역은 접근성 서비스가 좌표째로 거부한다
 */
class LoopEngine(
    private val ctx: Context,
    private val setup: LoopSetup,
    private val creds: Credentials,
    private val onLog: (String) -> Unit,
) {
    private val a11y get() = LoopAccessibilityService.instance

    // 게임 좌표 (2340x1080 가로 기준)
    private val tPanel = 368 to 977
    private val tDragon = 1076 to 164          // 즐겨찾기 대상이 오는 1번 슬롯
    private val tItemAwaken = 1305 to 486      // 각성 Lv+1
    private val tItemNormal = 1305 to 486      // 일반 LEVEL UP +1 (같은 자리에 온다)
    private val tLevelUp = 1416 to 930
    private val tItemOk = 997 to 786
    private val tResultOk = 1170 to 827
    private val tDlgX = 2028 to 56
    private val tMenu = 1977 to 88
    private val tSettings = 1974 to 641
    private val tSaveOk = 1170 to 663
    private val tSetX = 1647 to 87
    private val tSaveSafe = 842 to 890         // 저장 버튼 안, 계정삭제에서 가장 먼 지점
    private val tNoticeClose = 1170 to 1013
    private val tPopupX = 1873 to 117

    // 동굴 (대상 지정)
    private val tCave = 1714 to 88
    /** 상단 아이콘 줄의 '월드'. 어느 지도에서 눌러도 월드맵으로 돌아온다. */
    private val tWorld = 1839 to 88
    private val tGridOpen = 532 to 523
    private val tGridClose = 2003 to 523
    private val tSearch = 1515 to 103
    private val tFirstCell = 359 to 246
    private val tStar = 1732 to 936
    private val tCaveX = 2012 to 53

    // 동굴 필터. 여는 버튼과 '단계' 줄의 버튼 여섯 개.
    // ALL 은 켜지는 버튼이 아니라 전체 해제 버튼이다 (눌러도 금색이 되지 않는다).
    private val tFilter = 378 to 104
    private val tStageAll = 1191 to 146
    private fun stageButton(stage: Stage) = when (stage) {
        Stage.ADULT -> 1416 to 146
        Stage.CAPSULE -> 1642 to 146
        Stage.AWAKENED -> 1191 to 228
        Stage.TRANSCEND -> 1416 to 228
        Stage.DESCEND -> 1642 to 228
    }
    private val stageButtons get() = Stage.entries.map { it to stageButton(it) }
    /** 전체 화면 게임 창의 표준 닫기 X. 동굴 · 친구 목록 등이 같은 자리를 쓴다. */
    private val tWindowX = 2012 to 53

    // 탐험
    /** 지역 창의 '탐험' 버튼. 지역이 달라도 창은 같은 자리에 뜬다. */
    private val tExploreBtn = 1490 to 361
    /**
     * 대사를 넘기는 자리. 대사 상자 안이라 지도로 돌아왔을 때 덜 위험하다.
     * 가운데(1170,900)는 계정삭제 금지구역이라 탭이 거부된다. 왼쪽으로 옮겼다.
     */
    private val tExploreNext = 700 to 900
    /** FIGHT 육각형의 가로 위치. 세로는 화면마다 달라 판정으로 찾는다. */
    private val tFightX = 1750
    /**
     * 지역 창의 닫기 X. 전체 화면 창의 표준 X(2012,53) 와 자리가 다르다.
     * 그걸 모르고 표준 자리를 여섯 번 누르다 '동굴까지 가지 못했다' 로 멈췄다.
     */
    private val tZoneX = 1720 to 83

    // 로그인
    private val tStart = 1170 to 540
    private val tHighbrow = 1170 to 759
    private val tId = 1170 to 461
    private val tPw = 1170 to 339
    private val tLogin = 1170 to 699

    private var cycle = 0
    private var wins = 0
    /** 방금 돌린 탐험에서 레벨업 창을 봤는가. runOneExploration 이 채운다. */
    private var sawLevelUp = false
    /** 그 창에 찍혀 있던 실제 레벨. 못 읽었으면 null. */
    private var readLevel: Int? = null
    /** 그 창에 찍혀 있던 등급. 못 읽었으면 null. */
    private var readGrade: Double? = null
    private var level = setup.currentLevel

    /** 7.0 은 육각형이 시안이라 색으로 교차검증이 된다. 그 미만은 모두 금색이라 불가. */
    private val canVerifyGradeByColor = setup.currentGrade >= 6.95

    var abortReason: String? = null
        private set

    /**
     * 좌표 탭.
     *
     * 접근성 서비스는 계정삭제 금지구역이면 탭을 거부한다. 그건 옳은 동작인데,
     * 거부돼도 화면이 그대로라 루프는 눌린 줄 알고 다음 단계로 넘어갔다.
     * 실제로 월드맵의 탐험 좌표가 금지구역 안이라, 지도가 안 바뀐 채 다음 탭이
     * 엉뚱한 지역으로 들어갔고 원인을 찾는 데 한참 걸렸다.
     * 거부는 반드시 루프 로그에 남긴다.
     */
    private suspend fun tap(p: Pair<Int, Int>) {
        if (a11y?.tap(p.first, p.second) == false) {
            onLog("    !! 탭 거부됨 (${p.first},${p.second}) — 금지구역이거나 제스처 실패")
        }
    }

    private fun frame(): Bitmap? = ScreenCapture.frame()

    private suspend fun waitFor(timeoutMs: Long = 15000, pred: (Bitmap) -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            frame()?.let { if (pred(it)) return true }
            delay(400)
        }
        return false
    }

    private fun abort(reason: String) {
        abortReason = reason
        onLog("!! 중단: $reason")
        dumpFrame("abort")
        LoopStatus.requestStop()
    }

    /** 무엇을 보고 판단했는지 남긴다. 좌표가 어긋날 때 이것 없이는 원인을 못 찾는다. */
    private fun dumpFrame(tag: String) {
        val f = frame() ?: return
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: return
            java.io.File(dir, "frame_$tag.png").outputStream().use {
                f.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            onLog("   프레임 저장: ${f.width}x${f.height} → frame_$tag.png")
        }
    }

    private fun publish(phase: String) {
        LoopStatus.update(
            LoopStatus.current.copy(
                running = abortReason == null && !LoopStatus.stopRequested,
                level = level, cycle = cycle, wins = wins, phase = phase,
            )
        )
        FloatingService.refresh(ctx)
    }

    // ---------------- 본체 ----------------

    suspend fun run(maxCycles: Int = 1000) {
        if (setup.kind == LoopKind.EXPLORE) { runExplore(); return }

        onLog(
            "루프 시작: ${setup.dragon?.name} " +
                "Lv.${setup.currentLevel} → Lv.${setup.targetLevel} " +
                "(등급 ${String.format("%.1f", setup.currentGrade)} 유지)"
        )

        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }

        // 대상 지정은 한 번만 한다.
        // 즐겨찾기는 서버에 남아 데삭 후에도 유지되므로, 목표를 끝낼 때까지
        // 매번 동굴에 들어갈 이유가 없다. 매번 하면 시간도 들고,
        // 좌표가 빗나갈 때마다 엉뚱한 용을 건드릴 위험만 커진다.
        val prefs = LoopPrefs(ctx)
        val no = setup.dragon?.no ?: 0
        if (prefs.preparedNo == no && no != 0) {
            onLog("  대상 지정 건너뜀 (이미 즐겨찾기됨)")
            // 지정을 건너뛰어도 출발점은 동굴로 맞춘다.
            // 어디서 시작했는지 모르는 채 용 패널을 누르면 엉뚱한 화면을 건드린다.
            if (!ensureCaveGrid()) return
            if (!leaveCave()) return
        } else {
            if (!prepareTarget()) return
            prefs.preparedNo = no
        }
        if (!openDialog()) return

        while (cycle < maxCycles && !LoopStatus.stopRequested && abortReason == null) {
            cycle++
            publish("levelup")
            val win = doCycle() ?: return

            if (win) {
                wins++
                level++
                onLog("  누적 성공 $wins  (Lv.$level)")
                // 중단되더라도 여기서부터 이어갈 수 있게 남긴다
                LoopPrefs(ctx).currentLevel = level
                publish("save")
                if (!save()) return
                if (level >= setup.targetLevel) {
                    onLog("목표 달성. Lv.$level")
                    // 다음 드래곤이 1번 슬롯을 차지할 수 있게 별을 끈다
                    releaseTarget()
                    prefs.preparedNo = 0
                    LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
                    FloatingService.refresh(ctx)
                    return
                }
            } else {
                publish("desak")
                if (!desak()) return
                // 즐겨찾기는 서버에 남아 데삭 후에도 유지된다. 대상 지정을 다시 할 필요가 없다.
                if (!openDialog()) return
            }
        }
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "idle"))
        FloatingService.refresh(ctx)
    }

    /**
     * 탐험 루프.
     *
     * 레벨업 루프와 다른 점은 두 가지다.
     *  - 등급을 '그대로' 지키는 게 아니라 하한 아래로만 안 내려가면 된다
     *  - 레벨이 오르면 그 레벨에 맞는 탐험지로 옮겨간다
     *
     * 지금 들어 있는 것은 대상 지정까지다. 탐험 사이클(터치 진행 · FIGHT ·
     * 3마리 편성 · 레벨업 감지)은 화면 판정을 실기기로 맞춰가며 잇는다.
     * 판정 없이 좌표만 보고 누르면 엉뚱한 데를 건드린다 — 그건 이미 여러 번 겪었다.
     */
    private suspend fun runExplore() {
        val zone = ExploreMap.zoneFor(setup.currentLevel)
        onLog(
            "탐험 루프 시작: ${setup.dragon?.name} " +
                "Lv.${setup.currentLevel} → Lv.${setup.targetLevel} " +
                "(등급 ${String.format("%.1f", setup.minGrade)} 아래로 안 내려가게)"
        )
        onLog("  첫 탐험지: ${zone.area.label} → ${zone.name} (Lv.${zone.minLevel}, ${zone.party}마리)")

        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }

        // 탐험은 즐겨찾기가 아니라 '지금 선택된 용' 이 나간다.
        // 그 선택은 동굴에서 칸을 누르면 바뀌므로, 루프를 걸 때마다 다시 지정한다.
        // 건너뛰었더니 대표가 카멜레용에서 베네지로 바뀐 채 탐험을 돌았다.
        // 레벨업 루프처럼 한 번만 하고 넘어갈 수 있는 성질이 아니다.
        val prefs = LoopPrefs(ctx)
        if (!prepareTarget()) return
        prefs.preparedNo = setup.dragon?.no ?: 0

        // 동굴에서 나와 지도로. 여기부터는 지도 ↔ 탐험만 오간다.
        if (!leaveCave()) return

        // 레벨 뱃지 지문. 숫자를 읽지 않고 바뀌었는지만 본다.
        var badge = frame()?.takeIf { Judge.isGameTopBarVisible(it) }
            ?.let { Judge.levelBadge(it).first } ?: 0

        var runs = 0
        while (!LoopStatus.stopRequested && abortReason == null && runs < 500) {
            val z = ExploreMap.zoneFor(level)
            if (z.party > 1) {
                abort("${z.name} 은 3마리를 편성해야 한다 — 그 화면은 아직 못 다룬다")
                return
            }
            if (!openZoneDialog(z)) return

            runs++
            publish("explore")
            onLog("  탐험 $runs 회 — ${z.name}")
            val onZoneMap = runOneExploration() ?: return

            // 레벨업은 창을 본 것으로 센다. 뱃지 지문은 교차 검증용이다.
            if (sawLevelUp) {
                // 창에 찍힌 값이 있으면 그걸 믿는다. 세는 값은 어긋날 수 있고,
                // 어긋나면 레벨에 안 맞는 탐험지를 계속 도는 일이 생긴다.
                val shown = readLevel
                if (shown != null && shown != level) {
                    onLog("  세던 레벨 Lv.$level 을 화면값 Lv.$shown 로 맞춘다")
                    level = shown
                } else {
                    level++
                }
                prefs.currentLevel = level
                onLog("  레벨업 — Lv.$level")
                publish("explore")

                // 등급 하한. 아래로 내려가면 더 돌려봐야 목표를 못 지킨다.
                // 마이너스 레벨업으로 되돌리는 길은 아직 손대지 않았다. 그 화면을
                // 확인하지 않고 소모품을 쓰게 하면 안 된다. 지금은 멈추고 알린다.
                val g = readGrade
                if (g != null && g < setup.minGrade - 0.001) {
                    onLog("등급이 하한 아래로 내려갔다 — ${String.format("%.1f", g)} < ${String.format("%.1f", setup.minGrade)}")
                    dumpFrame("gradefloor")
                    abort("등급 하한을 지키지 못했다 (${String.format("%.1f", g)})")
                    return
                }
                if (level >= setup.targetLevel) {
                    onLog("목표 달성. Lv.$level")
                    LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
                    FloatingService.refresh(ctx)
                    return
                }
            }
            if (onZoneMap) {
                val f = frame()
                if (f != null && Judge.isGameTopBarVisible(f)) {
                    val (sig, white) = Judge.levelBadge(f)
                    // 창을 놓쳤는데 뱃지가 바뀌었다면 레벨 세는 값이 어긋난 것이다.
                    // 고치지는 않고 알리기만 한다 — 몇이 올랐는지는 아직 못 읽는다.
                    if (badge != 0 && sig != badge && !sawLevelUp) {
                        onLog("  ! 레벨업 창을 못 봤는데 뱃지가 바뀌었다 (흰 획 $white)")
                    }
                    badge = sig
                }
            }
        }
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "idle"))
        FloatingService.refresh(ctx)
    }

    /**
     * 지역 창을 연다.
     *
     * 지역 창이 떴다는 것은 지도에서 옳은 곳을 눌렀다는 뜻이다.
     * 이걸 확인하지 않고 탐험 버튼 자리를 누르면 지도 위 아무 데나 누르는 셈이 된다.
     *
     * @param fromZoneMap 방금 탐험을 마치고 그 지역 지도에 서 있으면 true.
     *                    그때는 월드맵을 거칠 필요가 없다.
     */
    private suspend fun openZoneDialog(z: Zone): Boolean {
        for (attempt in 1..3) {
            clearBlockers()

            // 탐험이 끝나면 보통 그 지역 지도로 돌아온다. 그런데 레벨업 창이 떴던
            // 판은 월드맵으로 돌려보낸다. 그걸 모르고 지역 좌표를 눌렀더니
            // 월드맵에서는 같은 자리가 바다라 엉뚱하게 에스텔라로 들어갔다.
            //
            // 그래서 월드맵인지만 본다. 월드맵이면 지역부터 골라 들어가고,
            // 아니면 이미 그 지역 지도에 서 있는 것이니 바로 누른다.
            // 매번 월드맵을 거치면 한 판마다 10초씩 버린다.
            val here = frame() ?: run { abort("화면을 읽지 못했다"); return false }
            if (Judge.isWorldMap(here) || !Judge.isGameTopBarVisible(here) || attempt > 1) {
                if (!goToWorldMap()) return false
                tap(z.area.at); delay(6000)
            }

            val f = frame() ?: run { abort("화면을 읽지 못했다"); return false }
            if (!Judge.isGameTopBarVisible(f)) {
                onLog("    지도가 아니다 ($attempt/3) — 다시 잡는다")
                continue
            }

            tap(z.at); delay(4000)
            val d = frame()
            if (d != null && Judge.isZoneDialog(d)) return true
            onLog("    지역 창이 안 열렸다 ($attempt/3) — 다시")
        }
        abort("${z.name} 지역 창을 열지 못했다")
        return false
    }

    /**
     * 탐험 한 판. 지역 지도로 돌아오면 끝이다.
     *
     * 탐험은 화면을 눌러 이벤트를 하나씩 넘기는 방식이다.
     * 아이템 · 경험치 · 몬스터 · 꽝 중 하나가 나오고, 몇 번 넘기면 지도로 돌아온다.
     *
     * 판단은 두 가지만 한다 — 확인된 판정만 쓴다.
     *   상단 아이콘 줄이 보이면  → 지도다, 끝났다
     *   FIGHT 육각형이 보이면   → 눌러서 싸운다
     *   그 밖에는              → 대사를 넘긴다
     *
     * @return 지역 지도에 서 있으면 true, 중단이면 null
     */
    private suspend fun runOneExploration(): Boolean? {
        tap(tExploreBtn); delay(6000)

        sawLevelUp = false
        readLevel = null
        readGrade = null
        var taps = 0
        while (taps < 60 && !LoopStatus.stopRequested) {
            val f = frame() ?: run { abort("탐험 중 화면을 읽지 못했다"); return null }

            if (Judge.isGameTopBarVisible(f)) return true

            // 배고픔 창. 확인을 누르면 음식을 먹여 회복한다.
            if (Judge.isHungerPopup(f)) {
                onLog("    배고픔 — 음식을 먹인다")
                tap(Judge.hungerOk); delay(3500)
                taps++
                continue
            }

            // 레벨업 창은 아무 데나 눌러서는 안 넘어간다. 확인을 눌러야 한다.
            if (Judge.isLevelUpScreen(f)) {
                val (mark, white) = Judge.gradeMark(f)
                sawLevelUp = true
                // 창에 찍힌 값이 진짜다. 세는 값은 어긋날 수 있다.
                val (shown, unknown) = Judge.levelUpLevel(f)
                readLevel = shown
                if (unknown != null) onLog("    ? 모르는 레벨 숫자: $unknown")

                val (grade, gradeUnknown) = Judge.readGrade(f)
                readGrade = grade
                if (gradeUnknown != null) onLog("    ? 모르는 등급 숫자: $gradeUnknown")

                onLog(
                    "    LEVEL UP — ${shown?.let { "Lv.$it" } ?: "레벨 못 읽음"} · " +
                        "등급 ${grade?.let { String.format("%.1f", it) } ?: "못 읽음 (자국 $mark, 흰 획 $white)"}"
                )
                // 등급 숫자를 아직 못 읽는다. 실제 등급을 아는 화면을 남겨
                // 판독표를 만든다. 추측으로 좌표를 잡으면 또 엉뚱한 데를 누른다.
                dumpFrame("levelup")
                tap(Judge.levelUpOk); delay(4000)
                taps++
                continue
            }

            val fy = Judge.fightButtonY(f)
            if (fy != null) {
                onLog("    몬스터 — 싸운다")
                tap(tFightX to fy); delay(6000)
                taps++
                continue
            }

            // 대사 넘기기. 화면 한가운데는 지도로 돌아온 뒤 누르면 위험해서
            // 대사 상자 자리를 누른다. 지도 판정이 먼저 걸러주지만 한 겹 더 둔다.
            tap(tExploreNext); delay(2500)
            taps++
        }
        if (LoopStatus.stopRequested) return null
        abort("탐험이 끝나지 않는다 ($taps 번 넘겼다)")
        return null
    }

    /** 레벨업 1회. true=트리플맥스, null=중단 */
    private suspend fun doCycle(): Boolean? {
        clearBlockers()   // 루프 도중에도 팝업·시스템 창은 언제든 끼어든다
        val f0 = frame() ?: run { abort("화면을 읽지 못했다"); return null }
        if (!Judge.isLevelUpDialog(f0)) { abort("사이클 시작 시 레벨업 다이얼로그가 아니다"); return null }

        tap(tLevelUp); delay(3000)
        tap(tItemOk); delay(4000)

        if (!waitFor(20000) { Judge.isResultPopup(it) }) {
            abort("레벨업 결과창이 안 뜬다"); return null
        }

        val f = frame() ?: run { abort("결과창을 읽지 못했다"); return null }
        val res = Judge.badges(f)
        val triple = res.values.all { it }
        val grade = Judge.gradeIs70(f)

        // 등급은 누적 평균이라 트리플 맥스면 지금 등급이 그대로 유지된다.
        // 하나라도 놓치면 떨어진다. 그래서 판정 기준은 뱃지 3개다.
        val win = triple

        val mark = res.entries.joinToString(" ") { "${it.key}${if (it.value) "O" else "X"}" }
        val gtxt = when (grade) { true -> "7.0"; false -> "7.0미만"; null -> "등급판정불가" }
        onLog("사이클 $cycle: $mark / $gtxt → ${if (win) "★트리플맥스★" else "실패"}")

        // 7.0 을 지키는 경우에만 색으로 교차검증이 가능하다.
        // 뱃지는 MAX 인데 시안이 아니면 판정 모델이 틀린 것이므로 저장하지 않고 멈춘다.
        if (canVerifyGradeByColor && triple && grade != true) {
            abort("판정 불일치 — 뱃지는 MAX 인데 등급이 7.0 이 아니다"); return null
        }

        tap(tResultOk); delay(3000)
        return win
    }

    /**
     * 대상 지정. 동굴에서 이름으로 찾아 고르고 즐겨찾기를 켠 뒤 저장한다.
     *
     * 데삭은 동굴 선택을 초기화한다. 즐겨찾기는 서버에 남으므로,
     * 별을 켜두면 매 사이클마다 사이드 패널 1번 슬롯에 그 용이 온다.
     * 이 단계가 없으면 엉뚱한 용에 아이템을 쓴다.
     */
    private suspend fun prepareTarget(): Boolean {
        val name = setup.dragon?.name ?: run { abort("대상 이름이 없다"); return false }
        onLog("  대상 지정: $name")
        publish("prepare")

        if (!openDragonDetail(name)) return false

        val f = frame() ?: run { abort("동굴 화면을 읽지 못했다"); return false }
        // 별 판정은 동굴 상세에서만 뜻이 있다. 지도 화면에서도 값이 나오기 때문에
        // 그대로 믿으면 월드맵에서 별 자리를 누르게 된다.
        if (Judge.isGameTopBarVisible(f)) {
            abort("동굴 상세가 아니다 — 즐겨찾기를 건드리지 않는다"); return false
        }
        when (Judge.isFavorited(f)) {
            true -> onLog("    즐겨찾기 이미 켜져 있음")
            false -> {
                tap(tStar); delay(3000)
                val after = frame()
                if (after == null || Judge.isFavorited(after) != true) {
                    abort("즐겨찾기를 켜지 못했다"); return false
                }
                onLog("    즐겨찾기 켬")
            }
            null -> { abort("즐겨찾기 상태를 판정할 수 없다"); return false }
        }

        tap(tCaveX); delay(4000)
        return saveOnly()
    }

    /**
     * 대상 해제. 목표를 끝냈으면 즐겨찾기를 끈다.
     *
     * 켜둔 채로 두면 다음 드래곤을 지정했을 때 1번 슬롯에 어느 쪽이 올지
     * 보장되지 않는다. 그러면 엉뚱한 용에 아이템을 쓴다.
     *
     * 여기서 실패해도 목표 자체는 이미 이뤘다. 중단으로 처리하지 않고
     * 사람이 손으로 끄도록 알리기만 한다.
     */
    private suspend fun releaseTarget() {
        val name = setup.dragon?.name ?: return
        onLog("  대상 해제: $name")

        if (!openDragonDetail(name)) {
            onLog("  ! 즐겨찾기를 끄지 못했다 — 직접 꺼야 한다"); return
        }
        val df = frame() ?: return
        if (Judge.isGameTopBarVisible(df)) {
            onLog("  ! 동굴 상세가 아니다 — 즐겨찾기를 건드리지 않는다"); return
        }
        when (Judge.isFavorited(df)) {
            false -> onLog("    즐겨찾기 이미 꺼져 있음")
            true -> {
                tap(tStar); delay(3000)
                val after = frame()
                if (after != null && Judge.isFavorited(after) == false) onLog("    즐겨찾기 끔")
                else onLog("  ! 즐겨찾기를 끄지 못했다 — 직접 꺼야 한다")
            }
            null -> onLog("  ! 즐겨찾기 상태를 판정할 수 없다 — 직접 확인해야 한다")
        }
        tap(tCaveX); delay(4000)
    }

    /**
     * 단계 필터를 설정한 단계 하나로만 맞춘다.
     *
     * 이름만으로는 대상을 못 고른다. 같은 이름이 성체 · 캡슐 · 각성 · 초월 · 강림으로
     * 여러 마리 있고, 검색 결과 첫 칸에 어느 쪽이 오는지 보장되지 않는다.
     * 실제로 샤크곤 캡슐을 지정했는데 이미 만렙인 강림이 잡혀 루프가 멈췄다.
     *
     * 버튼은 라디오가 아니라 토글이고 여러 개가 동시에 켜진다.
     * 켜져 있는 걸 또 누르면 꺼지므로, 누르기 전에 반드시 지금 상태를 읽는다.
     * 필터는 동굴을 닫아도 남아 있어서 다음 진입 때 그대로 걸려 있다.
     */
    private suspend fun applyStageFilter(): Boolean {
        val stage = setup.stage ?: return true    // 단계를 모르면 손대지 않는다
        val want = stageButton(stage)

        for (attempt in 1..3) {
            if (!openFilterPanel()) continue
            var f = frame() ?: continue

            // 다른 단계가 켜져 있으면 ALL 로 한 번에 턴다. ALL 은 전체 해제 버튼이다.
            val hasOther = stageButtons.any { (s, p) ->
                s != stage && Judge.isFilterButtonOn(f, p.first, p.second)
            }
            if (hasOther) {
                tap(tStageAll); delay(2000)
                f = frame() ?: continue
            }
            if (!Judge.isFilterButtonOn(f, want.first, want.second)) {
                tap(want); delay(2000)
                f = frame() ?: continue
            }

            val onlyWanted = stageButtons.all { (s, p) ->
                Judge.isFilterButtonOn(f, p.first, p.second) == (s == stage)
            }
            if (onlyWanted) {
                onLog("    단계 필터: ${stage.label}")
                return closeFilterPanel()
            }
            onLog("    단계 필터가 안 맞는다 ($attempt/3) — 다시")
            closeFilterPanel()
        }
        closeFilterPanel()
        return false
    }

    /** 필터창을 연다. 이미 열려 있으면 누르지 않는다 (누르면 닫힌다). */
    private suspend fun openFilterPanel(): Boolean {
        repeat(3) {
            val f = frame()
            if (f != null && Judge.isFilterOpen(f)) return true
            tap(tFilter); delay(2500)
        }
        return frame()?.let { Judge.isFilterOpen(it) } == true
    }

    /** 필터창을 닫는다. 닫혀 있으면 아무것도 누르지 않는다. */
    private suspend fun closeFilterPanel(): Boolean {
        repeat(3) {
            val f = frame() ?: return false
            if (!Judge.isFilterOpen(f)) return true
            tap(tFilter); delay(2500)
        }
        return false
    }

    /**
     * 어디에 있든 동굴 그리드까지 데려온다. 모든 루프의 출발점이다.
     *
     * 게임은 월드맵 · 탐험맵 · 던전 · 에스텔라 · 하늘 왕국 · 전투 · 각종 창 중
     * 어디서든 멈춰 있을 수 있다. 그런데 동굴 버튼은 지도류 화면이면 어디서나
     * 같은 자리(상단 아이콘 줄)에 있다. 그래서
     *
     *   1) 상단 아이콘 줄이 보일 때까지 덮고 있는 창을 닫고
     *   2) 동굴을 누르고
     *   3) 그리드를 펼쳐 검색칸이 보이는지로 확인한다
     *
     * 세 번째가 핵심이다. '동굴에 들어왔다' 를 눈으로 확인하지 않으면
     * 그 뒤의 탭이 전부 엉뚱한 화면으로 날아간다. 실제로 그렇게 지도가 넘어가고
     * 도감 검색창까지 들어간 적이 있다.
     */
    private suspend fun ensureCaveGrid(): Boolean {
        if (!waitForGameReady()) return false

        // 탐험이 진행 중이었다는 것이 한 번이라도 확인되면, 그 뒤로는 모르는 화면도
        // 대사 넘기기로 다룬다. 전투가 끝난 뒤의 대사 화면은 창을 닫아서 나갈 수 없다.
        var inExploration = false

        for (round in 1..20) {
            clearBlockers()
            val f = frame() ?: run { abort("화면을 읽지 못했다"); return false }

            if (Judge.isCaveGrid(f) || Judge.isFilterOpen(f)) return true

            if (Judge.isGameTopBarVisible(f)) {
                tap(tCave); delay(5000)
                clearBlockers()

                // 그리드가 이미 펼쳐져 있으면 여는 화살표 자리는 드래곤 칸이다.
                // 확인 없이 누르면 거기 있는 용이 선택되어 대표가 바뀐다.
                // 실제로 그렇게 카멜레용이 베네지로 바뀌었다.
                val c = frame()
                if (c == null || !Judge.isCaveGrid(c)) {
                    tap(tGridOpen); delay(4000)
                }

                val g = frame()
                if (g != null && (Judge.isCaveGrid(g) || Judge.isFilterOpen(g))) {
                    onLog("    동굴 그리드 진입")
                    return true
                }
                onLog("    동굴 진입 확인 실패 ($round/6) — 되돌린다")
                tap(tWindowX); delay(4000)
            } else if (finishExploration(f)) {
                // 탐험이나 전투가 진행 중이었다. 그건 창을 닫아서 빠져나올 수 없다.
                // 재시작이 전투 중에 걸리면 표준 X 를 여섯 번 눌러도 화면이 그대로다.
                inExploration = true
            } else if (inExploration) {
                tap(tExploreNext); delay(2500)
            } else {
                // 지도가 아니라 큰 창이다. 표준 닫기 X 자리를 한 번 누른다.
                onLog("    지도 화면이 아니다 ($round/20) — 창 닫기")
                tap(tWindowX); delay(4000)
            }
        }
        abort("동굴까지 가지 못했다")
        return false
    }

    /**
     * 탐험이나 전투가 진행 중이면 한 걸음 진행시킨다.
     *
     * 그 화면들은 창을 닫아서 빠져나올 수 없다. 전투에는 닫기 X 가 아예 없다.
     * 재시작이 전투 중에 걸리면 표준 X 를 여섯 번 눌러도 화면이 그대로였다.
     *
     * @return 아는 화면이라 손을 댔으면 true
     */
    private suspend fun finishExploration(f: Bitmap): Boolean {
        if (Judge.isHungerPopup(f)) {
            onLog("    배고픔 — 음식을 먹인다"); tap(Judge.hungerOk); delay(3500); return true
        }
        if (Judge.isLevelUpScreen(f)) {
            onLog("    레벨업 창 — 확인"); tap(Judge.levelUpOk); delay(4000); return true
        }
        val fy = Judge.fightButtonY(f)
        if (fy != null) {
            onLog("    전투 중 — 싸운다"); tap(tFightX to fy); delay(6000); return true
        }
        return false
    }

    /**
     * 동굴에서 나와 지도까지 되돌아간다.
     *
     * 닫기 X 한 번으로는 부족하다. 그리드가 펼쳐져 있으면 그리드만 닫히고
     * 동굴 상세에 남는다. 실제로 그 상태에서 지도인 줄 알고 지역을 눌렀다.
     * 상단 아이콘 줄이 보일 때까지 닫는다.
     */
    private suspend fun leaveCave(): Boolean {
        repeat(6) {
            val f = frame()
            if (f != null && Judge.isGameTopBarVisible(f)) return true

            when {
                // 필터창이 덮고 있으면 그것부터. 아래로 눌러봐야 창에 막힌다.
                f != null && Judge.isFilterOpen(f) -> { tap(tFilter); delay(2500) }
                // 그리드가 펼쳐져 있으면 빨간 X 가 먹지 않는다. 옆 화살표로 먼저 접는다.
                // adb 로 직접 눌러도 안 닫히는 것을 확인했다.
                f != null && Judge.isCaveGrid(f) -> { tap(tGridClose); delay(3500) }
                else -> { tap(tCaveX); delay(4000) }
            }
        }
        val f = frame()
        if (f != null && Judge.isGameTopBarVisible(f)) return true
        abort("동굴에서 지도로 나가지 못했다")
        return false
    }

    /**
     * 월드맵까지 나온다.
     *
     * 상단 아이콘 줄의 '월드' 버튼은 어느 지도에서 눌러도 월드맵으로 데려온다.
     * 월드맵에서 눌러도 그대로 있는다 — 몇 번 눌러도 안전하다.
     *
     * 동굴을 닫으면 월드맵이 아니라 '동굴을 열었던 지도' 로 돌아간다.
     * 그걸 월드맵으로 여기고 탐험 좌표를 눌러 에스텔라로 들어간 적이 있다.
     */
    private suspend fun goToWorldMap(): Boolean {
        repeat(4) {
            clearBlockers()
            val f = frame() ?: run { abort("화면을 읽지 못했다"); return false }
            if (Judge.isWorldMap(f)) return true

            if (Judge.isGameTopBarVisible(f)) {
                tap(tWorld); delay(5000)
            } else {
                // 큰 창에 막혀 월드 버튼이 안 보인다. 동굴을 거쳐 지도로 되돌린다.
                if (!ensureCaveGrid()) return false
                if (!leaveCave()) return false
            }
        }
        val f = frame()
        if (f != null && Judge.isWorldMap(f)) return true
        abort("월드맵까지 나가지 못했다")
        return false
    }

    /** 동굴에서 이름으로 찾아 상세를 연다. 지정·해제가 함께 쓴다. */
    private suspend fun openDragonDetail(name: String): Boolean {
        // 검색창에 글자가 들어갔는지를 성공 기준으로 삼아 재시도한다.
        // (접근성으로 입력하므로 한글이 들어간다 — adb 로는 불가능했다)
        var typed = false
        for (attempt in 1..3) {
            if (!ensureCaveGrid()) return false
            if (!applyStageFilter()) {
                onLog("    단계 필터 실패 ($attempt/3) — 되돌리고 재시도")
                tap(tCaveX); delay(3000)
                continue
            }
            tap(tSearch); delay(2500)
            if (typeText(name)) { typed = true; break }
            onLog("    검색 진입 실패 ($attempt/3) — 되돌리고 재시도")
            tap(tCaveX); delay(3000)   // 어디에 있는지 모르니 동굴을 닫아 월드맵으로
        }
        if (!typed) {
            // 입력이 안 됐는데 진행하면 엉뚱한 용을 고른다.
            abort("검색어를 입력하지 못했다")
            return false
        }
        delay(1500)

        // 입력이 됐을 때만 키보드를 내린다.
        // 키보드가 안 떠 있으면 이 뒤로가기가 게임 종료창을 띄운다.
        a11y?.back(); delay(2500)

        tap(tFirstCell); delay(3000)
        tap(tGridClose); delay(3000)
        return true
    }

    /**
     * 게임이 자리를 잡을 때까지 기다리며 방해물을 걷어낸다.
     *
     * 앱이 게임을 띄우자마자 루프가 시작되는데, 그 시점엔 아직 로딩 중이라
     * 팝업이 없다. 곧바로 진행하면 그 뒤에 뜬 팝업 위로 탭을 날리게 된다.
     * 이벤트·공지 팝업은 로딩이 끝난 다음에 온다.
     *
     * 조용한 상태가 연속으로 확인될 때까지 기다린다.
     * 모르는 화면이면 억지로 누르지 않는다 — 뒤 단계 검증이 걸러낸다.
     */
    /**
     * 지금 화면을 가리는 팝업만 즉시 걷어낸다.
     * 이벤트 팝업은 로딩이 끝나고도 한참 뒤에 뜨기 때문에,
     * 단계를 넘어갈 때마다 한 번씩 확인해야 한다.
     */
    private suspend fun clearBlockers() {
        // 일일 팝업은 보물상자 → 보상 → 출석체크판 식으로 줄줄이 이어진다.
        // 한두 번으로는 다 못 걷어낸다.
        repeat(8) {
            // 여기서 화면을 '옮기는' 일은 하지 않는다. 가리는 것만 걷어낸다.
            // 예전에는 동굴이면 월드맵으로 되돌렸다. 그 판정이 틀렸던 데다
            // (숲 화면을 동굴로 읽었다) 이제는 동굴이 출발점이라 되돌리면 서로 싸운다.

            // 게임 밖 창이 올라왔는가 (재난문자 · 전화 · 다른 앱 알림)
            val pkg = a11y?.foregroundPackage()
            if (pkg != null && pkg != GAME_PKG && pkg != ctx.packageName) {
                // 다이얼로그면 닫기 버튼으로 물러난다.
                // 앱이 통째로 올라온 것이면 닫을 버튼이 없다 — 실제로 카카오톡이
                // 올라온 채 '닫기 시도' 만 여덟 번 반복하다 멈췄다.
                // 한 번 시도해서 안 물러나면 게임을 다시 앞으로 끌어온다.
                onLog("    방해 창 감지: $pkg — 닫기 시도")
                a11y?.dismissSystemDialog()
                delay(2500)
                if (a11y?.foregroundPackage() != GAME_PKG) {
                    onLog("    안 물러난다 — 게임을 다시 앞으로")
                    if (!ensureGameForeground()) return
                    delay(3000)
                }
                return@repeat
            }
            val f = frame() ?: return
            // 배고픔 창은 지도에서도 뜬다. 확인을 눌러 음식을 먹인다.
            if (Judge.isHungerPopup(f)) {
                onLog("    배고픔 — 음식을 먹인다"); tap(Judge.hungerOk); delay(3500)
                return@repeat
            }
            // 탐험 지역 창. 닫기 X 가 전체 화면 창의 표준 자리와 다르다.
            // 표준 자리를 여섯 번 누르다 '동굴까지 가지 못했다' 로 멈춘 적이 있다.
            if (Judge.isZoneDialog(f)) {
                onLog("    지역 창 닫기"); tap(tZoneX); delay(3000)
                return@repeat
            }
            // 공지 판정(isNotice)은 여기서 쓰지 않는다. 월드맵 하단도 어두워
            // 오탐이 나고, 그러면 같은 자리를 계속 누르게 된다.
            // 공지는 로그인 직후 문맥에서만 확인한다.
            if (Judge.hasPopupCloseX(f)) {
                onLog("    팝업 닫기"); tap(tPopupX); delay(2500)
                return@repeat
            }
            // X 없이 '확인' 만 있는 모달 (보물상자 등).
            // 레벨업 다이얼로그를 잘못 건드리지 않도록 그때는 손대지 않는다.
            val cy = if (Judge.isLevelUpDialog(f)) null else Judge.confirmPopupY(f)
            if (cy != null) {
                onLog("    확인 팝업 닫기 (y=$cy)"); tap(1166 to cy); delay(2500)
            } else return
        }
    }

    /**
     * 게임이 앞에 없으면 다시 띄운다.
     *
     * 데삭으로 게임을 재실행하는 사이 다른 앱이 올라오는 일이 있다.
     * 실제로 삼성 뉴스가 앞에 뜬 상태에서 로그인을 시도하다 멈췄다.
     * 어느 화면에서 시작하든 게임부터 앞으로 끌어와야 한다.
     */
    private suspend fun ensureGameForeground(): Boolean {
        repeat(3) {
            // 멈추라고 했으면 게임을 다시 띄우지 않는다.
            // 이게 없으면 앱을 닫아도 루프가 드빌을 계속 올려 손을 못 쓴다.
            if (LoopStatus.stopRequested) return false
            val pkg = a11y?.foregroundPackage()
            if (pkg == GAME_PKG) return true
            onLog("    게임이 앞에 없다 (현재: $pkg) — 다시 띄운다")
            ctx.packageManager.getLaunchIntentForPackage(GAME_PKG)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            } ?: run { abort("게임을 실행하지 못했다"); return false }
            delay(8000)
        }
        return a11y?.foregroundPackage() == GAME_PKG
    }

    private suspend fun waitForGameReady(timeoutMs: Long = 90000): Boolean {
        onLog("    게임 준비 대기")
        if (!ensureGameForeground()) {
            abort("게임을 앞으로 가져오지 못했다"); return false
        }
        val end = System.currentTimeMillis() + timeoutMs
        var quiet = 0
        while (System.currentTimeMillis() < end) {
            if (LoopStatus.stopRequested) return false
            // 도중에 다른 앱이 끼어들 수 있다
            if (a11y?.foregroundPackage()?.let { it != GAME_PKG && it != ctx.packageName } == true) {
                if (!ensureGameForeground()) return false
                quiet = 0
            }
            val f = frame()
            if (f == null) { delay(1500); continue }
            when {
                Judge.isTitle(f) -> {
                    onLog("    타이틀 화면 — 로그인부터")
                    if (!login()) return false
                    quiet = 0
                }
                // 로그인 선택/입력 화면도 '로그아웃 상태' 다.
                // 이걸 안 보고 준비됐다고 판단해서, 로그인 화면 위로
                // 동굴·검색 탭을 날리다 삼성 키보드 설정까지 들어갔다.
                Judge.isLoginSelect(f) -> {
                    onLog("    로그인 선택 화면 — 하이브로로 로그인")
                    if (!loginFromSelect()) return false
                    quiet = 0
                }
                Judge.isIdForm(f) -> {
                    onLog("    아이디 입력폼 — 자격증명 입력")
                    if (!loginFromForm()) return false
                    quiet = 0
                }
                Judge.isNotice(f) -> {
                    onLog("    공지 닫기"); tap(tNoticeClose); delay(4000); quiet = 0
                }
                Judge.hasPopupCloseX(f) -> {
                    onLog("    팝업 닫기"); tap(tPopupX); delay(2500); quiet = 0
                }
                // X 없이 '확인' 만 있는 모달 (보물상자 등)
                Judge.confirmPopupY(f) != null -> {
                    val cy = Judge.confirmPopupY(f)!!
                    onLog("    확인 팝업 닫기 (y=$cy)"); tap(1166 to cy); delay(2500); quiet = 0
                }
                else -> {
                    quiet++
                    // 이벤트 팝업은 공지보다 한참 뒤에 온다. 넉넉히 기다린다.
                    if (quiet >= 7) { onLog("    게임 준비됨"); return true }
                    delay(2500)
                }
            }
        }
        abort("게임이 준비되지 않았다 (팝업이 계속 뜨거나 로딩이 끝나지 않음)")
        return false
    }

    /** 설정창을 열어 저장만 한다 (다이얼로그 복귀 없이). */
    private suspend fun saveOnly(): Boolean {
        var ok = false
        repeat(3) { attempt ->
            tap(tMenu); delay(2000)
            tap(tSettings); delay(3000)
            frame()?.let { if (Judge.isSettingsOpen(it)) ok = true }
            if (ok) return@repeat
            onLog("    설정창 미확인 (${attempt + 1}/3) — 재시도")
        }
        if (!ok) { abort("설정창 검증 3회 실패"); return false }
        tap(tSaveSafe); delay(3000)
        tap(tSaveOk); delay(2000)
        tap(tSetX); delay(2000)
        tap(tMenu); delay(2000)
        onLog("    저장 완료 (복구 지점)")
        return true
    }

    /** 레벨업 다이얼로그까지 들어간다. 용 패널은 토글이라 어긋나면 다시 시도한다. */
    private suspend fun openDialog(): Boolean {
        clearBlockers()
        val f = frame()
        if (f != null && Judge.isTitle(f)) {
            onLog("  타이틀 화면 — 로그인부터")
            if (!login()) return false
        }
        val item = if (setup.stage?.awakened == true) tItemAwaken else tItemNormal
        // 용 패널은 토글이다. 한 번 어긋나면 재시도마다 열고 닫기를 반복해
        // 영원히 못 연다 (실제로 이렇게 멈췄다).
        // 그래서 '닫혀 있다' 와 '이미 열려 있다' 를 번갈아 가정한다.
        var tries = 0      // 실제로 패널 여는 순서를 밟은 횟수
        var rounds = 0     // 창 닫기까지 포함한 전체 반복
        while (tries < 4 && rounds < 8) {
            rounds++
            // 알림 배너를 잘못 눌러 다른 앱이 떴을 수 있다. 매 시도마다 확인한다.
            clearBlockers()
            val f = frame()
            if (f != null && Judge.isTitle(f)) { abort("로그아웃 상태로 되돌아갔다"); return false }

            // 큰 창이 덮고 있으면 좌표 탭이 엉뚱한 곳에 꽂힌다.
            // 실제로 친구 목록 창이 열린 채 같은 자리를 계속 두드렸다.
            // 여기서는 '덮여 있다' 를 이미 확인했으므로 창 닫기 X 를 눌러도
            // 월드맵의 N 배지를 잘못 누를 일이 없다.
            if (f != null && !Judge.isGameTopBarVisible(f)) {
                onLog("    큰 창이 덮고 있다 — 창 닫기")
                tap(tWindowX); delay(3500)
                continue
            }

            if (tries % 2 == 0) { tap(tPanel); delay(4000) }
            tap(tDragon); delay(3000)
            tap(item); delay(3000)
            if (waitFor(12000) { Judge.isLevelUpDialog(it) }) return true
            tries++
            onLog("    다이얼로그 미확인 ($tries/4) — 재시도")
        }
        abort("레벨업 다이얼로그가 안 열린다 ($tries 회 시도)")
        return false
    }

    /** 메뉴 → 설정 → 저장. 설정창 검증을 게이트로 3회 재시도한다. */
    private suspend fun save(): Boolean {
        onLog("  저장 진행")
        tap(tDlgX); delay(2000)
        frame()?.let { if (Judge.isLevelUpDialog(it)) { tap(tDlgX); delay(2000) } }

        var ok = false
        repeat(3) { attempt ->
            tap(tMenu); delay(2000)
            tap(tSettings); delay(3000)
            frame()?.let { if (Judge.isSettingsOpen(it)) ok = true }
            if (ok) return@repeat
            onLog("    설정창 미확인 (${attempt + 1}/3) — 재시도")
        }
        if (!ok) { abort("설정창 검증 3회 실패"); return false }

        tap(tSaveSafe); delay(3000)
        tap(tSaveOk); delay(2000)
        tap(tSetX); delay(2000)
        tap(tMenu); delay(2000)
        onLog("  저장 완료")
        return openDialog()
    }

    /**
     * 데삭 — 게임 데이터를 지워 마지막 저장 시점으로 되돌린다.
     *
     * 일반 앱은 다른 앱의 데이터를 지울 수 없다.
     * 설정 앱을 접근성으로 대신 조작한다. 기종마다 배치가 달라 **글자로 찾아** 누른다.
     */
    private suspend fun desak(): Boolean {
        onLog("  데삭 실행")
        val s = a11y ?: run { abort("접근성 서비스가 꺼졌다"); return false }

        // 여기서부터 설정 앱과 게임을 오간다. 그 사이 태스크가 정리되는데,
        // 그걸 '사용자가 앱을 닫았다' 로 읽으면 루프가 통째로 멈춘다.
        // 일부러 나가 있는 중이라고 알려 둔다.
        LoopStatus.beginLeaving()
        try {
            return desakSteps(s)
        } finally {
            LoopStatus.endLeaving()
        }
    }

    private suspend fun desakSteps(s: LoopAccessibilityService): Boolean {

        // 설정 앱이 이미 다른 화면(예: 접근성)에 열려 있으면 그 화면이 그대로
        // 재사용되어 앱 정보가 뜨지 않는다. CLEAR_TASK 로 새로 띄운다.
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$GAME_PKG")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
        )
        delay(2500)
        onLog("    설정 화면: pkg=${s.foregroundPackage()} 저장공간=${s.hasText("저장공간")}")

        // ★ 드빌의 앱 정보 화면이 맞는지 먼저 확인한다.
        // 설정 앱이 다른 화면(접근성 등)에 열려 있으면 그 화면이 그대로 남는데,
        // 그때 '데이터 삭제' 를 글자로 찾다가 그 화면의 토글을 눌러
        // 드빌 매크로 자신의 접근성을 꺼버린 적이 있다.
        // 게임 앱 이름은 '드래곤빌리지' 가 아니라 '드빌' 이다(실측).
        if (!s.waitForText("애플리케이션 정보", "앱 정보", "App info", timeoutMs = 15000) ||
            !s.hasText("드빌")
        ) {
            abort("드빌의 앱 정보 화면이 아니다 — 아무것도 누르지 않고 멈춘다")
            return false
        }

        // '저장공간' 은 한 화면에 안 들어온다. 스크롤해서 찾는다.
        if (!s.scrollUntilText("저장공간", "저장 공간", "Storage")) {
            abort("저장공간 항목을 찾지 못했다"); return false
        }
        if (!clickRetry(s, 3, "저장공간", "저장 공간", "Storage")) {
            abort("저장공간 항목을 누르지 못했다"); return false
        }
        delay(1500)
        if (!s.scrollUntilText("데이터 삭제", "Clear data")) {
            abort("저장공간 화면을 찾지 못했다"); return false
        }
        if (!clickRetry(s, 3, "데이터 삭제", "Clear data")) {
            abort("데이터 삭제 버튼을 누르지 못했다"); return false
        }
        // 확인 대화상자
        delay(1500)
        clickRetry(s, 3, "삭제", "확인", "OK", "Delete")
        delay(3000)

        // 게임 재실행
        ctx.packageManager.getLaunchIntentForPackage(GAME_PKG)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        } ?: run { abort("게임을 실행하지 못했다"); return false }

        delay(22000)
        if (!ensureGameForeground()) return false
        return login()
    }

    /** 접근성 트리가 늦게 채워질 수 있어 몇 번 더 시도한다. */
    private suspend fun clickRetry(
        s: LoopAccessibilityService,
        times: Int,
        vararg texts: String,
    ): Boolean {
        repeat(times) {
            if (s.clickByText(*texts)) return true
            delay(1200)
        }
        return false
    }

    /** 반드시 하이브로 계정으로만 로그인한다. 확인 안 되면 중단. */
    private suspend fun login(): Boolean {
        onLog("  로그인 시작")
        if (!ensureGameForeground()) return false
        tap(tStart); delay(5000)

        // 구글로 새는 사고 방지 — 3버튼 선택 화면을 확인해야만 진행
        if (!waitFor(20000) { Judge.isLoginSelect(it) }) {
            abort("로그인 선택 화면이 아니다 (구글 자동로그인 의심)"); return false
        }
        return loginFromSelect()
    }

    /** 로그인 선택 화면(구글/페북/하이브로)에서 이어간다. */
    private suspend fun loginFromSelect(): Boolean {
        // 버튼 y 가 회차마다 달라 실제 위치를 찾아 누른다
        val hy = frame()?.let { Judge.highbrowButtonY(it) } ?: tHighbrow.second
        a11y?.tap(tHighbrow.first, hy); delay(3000)

        // 하이브로 입력폼이 떠야만 자격증명을 넣는다.
        // 다만 이미 로그인된 계정이면 게임이 스스로 넘어가버려 입력폼이 오지 않는다.
        // 그때는 공지창이 뜨거나 월드맵으로 들어간다 — 그건 실패가 아니라 성공이다.
        var sawForm = false
        val end = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < end) {
            val f = frame()
            if (f != null) {
                if (Judge.isIdForm(f)) { sawForm = true; break }
                if (Judge.isNotice(f)) {
                    onLog("  이미 로그인되어 있다 — 공지만 닫는다")
                    repeat(3) {
                        val n = frame() ?: return@repeat
                        if (!Judge.isNotice(n)) return@repeat
                        tap(tNoticeClose); delay(6000)
                    }
                    return true
                }
            }
            delay(500)
        }
        if (!sawForm) {
            // 로그인 화면도 입력폼도 아니면 이미 들어와 있는지 본다
            if (a11y?.foregroundPackage() == GAME_PKG &&
                frame()?.let { !Judge.isTitle(it) && !Judge.isLoginSelect(it) } == true
            ) {
                onLog("  로그인 화면을 벗어났다 — 이미 로그인된 것으로 본다")
                return true
            }
            abort("하이브로 아이디 입력폼이 안 뜬다 — 잘못된 로그인 경로"); return false
        }
        return loginFromForm()
    }

    /** 아이디 입력폼에서 이어간다. */
    private suspend fun loginFromForm(): Boolean {
        tap(tId); delay(1500)
        if (!typeText(creds.id, expectPassword = false)) { abort("아이디 칸에 포커스가 오지 않았다"); return false }
        delay(800)
        tap(tPw); delay(1500)
        // 여기서 칸을 확인하지 않으면 비밀번호가 아이디 칸에 평문으로 들어간다.
        // 실제로 그렇게 되어 화면에 비밀번호가 그대로 보였다.
        if (!typeText(creds.password, expectPassword = true)) { abort("비밀번호 칸에 포커스가 오지 않았다"); return false }
        delay(800)
        a11y?.back(); delay(1500)
        tap(tLogin); delay(8000)

        if (!waitFor(25000) { Judge.isNotice(it) }) {
            abort("로그인 후 공지창이 안 뜬다"); return false
        }
        repeat(3) {
            val f = frame() ?: return@repeat
            if (!Judge.isNotice(f)) return@repeat
            tap(tNoticeClose); delay(6000)
        }
        onLog("  로그인 완료 (하이브로 확인됨)")
        return true
    }

    /**
     * 접근성으로 입력칸에 글자를 넣는다. adb 와 달리 한글도 들어간다.
     *
     * 입력칸을 탭한 직후에는 포커스가 아직 잡히지 않는다.
     * 실제로 아이디는 비고 비밀번호만 들어가는 일이 있었다.
     * 포커스가 생길 때까지 기다렸다가 넣고, 넣은 뒤 값까지 확인한다.
     */
    private suspend fun typeText(text: String, expectPassword: Boolean? = null): Boolean {
        val s = a11y ?: return false
        repeat(12) {
            val root = s.rootInActiveWindow
            val focused = root?.findFocus(
                android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
            )
            // 포커스가 '누른 그 칸' 인지 확인한다.
            // 확인 없이 넣었다가 비밀번호가 아이디 칸에 들어가 화면에 그대로 보였다.
            // 탭이 안 먹으면 포커스는 이전 칸에 남아 있고, SET_TEXT 는 그 칸을 덮어쓴다.
            //
            // 좌표로는 구분할 수 없다 — 하이브로 로그인은 WebView 이고
            // 노드가 보고하는 세로 좌표가 실제 화면과 다르다(전체가 [0,0][2340,441] 로 보고됨).
            // 대신 비밀번호 칸만 isPassword 가 true 다. 이게 정확한 구분자다.
            if (focused != null && expectPassword != null && focused.isPassword != expectPassword) {
                runCatching { root.recycle() }
                delay(500)
                return@repeat
            }
            if (focused != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo
                            .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                val ok = focused.performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
                )
                runCatching { root.recycle() }
                if (ok) return true
            } else {
                runCatching { root?.recycle() }
            }
            delay(500)
        }
        onLog("    !! 입력 포커스를 찾지 못했다")
        return false
    }
}
