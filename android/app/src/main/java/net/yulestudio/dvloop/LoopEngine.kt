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

    // 프리미엄 레벨업을 쓰는 용(각성이 아닌 용) 전용. 2026-08-13 전부 실측.
    private val tItemSlotUp = 1399 to 725     // 사용 아이템(업) 칸 → 아이템 선택 창
    private val tPickPremium = 1454 to 532
    private val tPickSpecial = 1732 to 532
    private val tLevelDown = 1806 to 930      // 레벨 다운 (Lv - 1 사용)
    private val tItemCancel = 1341 to 786
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

    // 레벨 필터. ALL · 1 · 20 / 21 · 35 · 45 두 줄이다. 실측 y 848 · 924.
    private val tLevelAll = 1191 to 848
    private val tLevel45 = 1642 to 924
    private val levelButtons = listOf(
        1191 to 848, 1416 to 848, 1642 to 848,
        1191 to 924, 1416 to 924, 1642 to 924,
    )
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

    /** 목표 레벨에 닿아 끝났는가. 대기열의 다음 드래곤으로 넘어갈지 서비스가 이걸 본다. */
    var reachedTarget = false
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
        if (setup.kind == LoopKind.MISSION) { runMission(); return }
        if (setup.kind == LoopKind.ARHA_SEAL) { runArhaSeal(); return }
        if (setup.kind == LoopKind.ARHA_BOSS) { runArhaRaid(); return }

        onLog(
            "루프 시작: ${setup.dragon?.name} " +
                "Lv.${setup.currentLevel} → Lv.${setup.targetLevel} " +
                "(지금 ${String.format("%.1f", setup.currentGrade)} · " +
                "${String.format("%.1f", setup.minGrade)} 아래로 안 내려가게)"
        )

        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }

        // 대상은 루프를 걸 때마다 다시 지정한다.
        //
        // 예전에는 '즐겨찾기가 데삭 후에도 서버에 남으니 한 번만 하면 된다' 고 보고
        // 같은 용이면 건너뛰었다. 그 전제가 깨지는 경우를 여럿 겪었다.
        //  - 동굴 그리드에서 탭이 빗나가면 선택된 용이 바뀐다
        //  - 즐겨찾기가 둘 이상이면 1번 슬롯에 어느 쪽이 올지 보장되지 않는다
        //  - 설정을 바꿔도 같은 용이면 단계 필터가 다시 걸리지 않는다
        //    (샤크곤을 캡슐로 지정했는데 강림이 잡혀 멈춘 적이 있다)
        // 40초쯤 더 걸리지만 어긋날 자리가 없어진다. 탐험 루프와 같은 방식이다.
        val prefs = LoopPrefs(ctx)
        if (!prepareTarget()) return
        prefs.preparedNo = setup.dragon?.no ?: 0
        if (!openDialog()) return
        // 무엇이 물려 있는지 모르는 채 시작하지 않는다. 지난 사이클에서
        // 스페셜을 쓰고 끝났으면 그대로 남아 있다.
        if (usesPremium && !selectUpItem(Judge.UpItem.PREMIUM)) return

        while (cycle < maxCycles && !LoopStatus.stopRequested && abortReason == null) {
            cycle++
            publish("levelup")
            val win = doCycle() ?: return

            if (win) {
                fails = 0
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
                    reachedTarget = true
                    LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
                    FloatingService.refresh(ctx)
                    return
                }
            } else if (!handleFailure()) {
                return
            }
        }
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "idle"))
        FloatingService.refresh(ctx)
    }

    // ---------------- 아르하 봉인지 탐험 루프 ----------------

    /**
     * 봉인지를 자동 탐험으로 돈다. 순서는 빛 → 바람 → 불 → 물 → 땅 → 어둠.
     *
     * 한 판씩 눌러서는 아이템 100개를 못 채운다. 자동 탐험은 전투까지 게임이
     * 알아서 하므로 루프가 할 일은 **켜고 · 끊기면 다시 켜고 · 시간이 되면
     * 끄고 다음 봉인지로** 가는 것뿐이다.
     *
     * 2026-08-17 맥 스크립트로 여섯 봉인지 60분 무인 완주를 확인하고 옮겼다.
     */
    private suspend fun runArhaSeal() {
        onLog("아르하 봉인지 탐험 시작")
        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }
        if (!waitForGameReady()) return

        publish("arha")
        if (!goToArha()) return
        checkArhaBag()
        for (seal in ArhaMap.Seal.entries) {
            if (LoopStatus.stopRequested || abortReason != null) break
            onLog("  [${seal.label}]")
            when (arhaOneSeal(seal)) {
                ArhaResult.RAID -> {
                    onLog("  레이드 편성 화면 — 20자리를 채워야 한다. 여기서 멈춘다")
                    dumpFrame("arha-raid")
                    return
                }
                ArhaResult.FAILED -> {
                    onLog("  [${seal.label}] 자동 탐험을 켜지 못했다 — 다음으로 넘어간다")
                }
                ArhaResult.DONE -> Unit
            }
        }
        reachedTarget = abortReason == null
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
        FloatingService.refresh(ctx)
    }

    /**
     * 아르하 레이드 루프 — **수동 탐험**으로 레이드를 띄운다.
     *
     * 자동 탐험으로는 레이드가 안 걸린다. 그래서 봉인지 탐험 루프와 방식이
     * 다르다. 한 판씩 눌러 돌면서 몬스터가 나오면 설정에서 고른 속성으로
     * 편성하고, 레이드가 뜨면 멈춘다 (20자리 편성은 아직 사람이 한다).
     */
    private suspend fun runArhaRaid() {
        val seal = setup.arhaSeal ?: run { abort("봉인지가 정해지지 않았다"); return }
        val elements = setup.arhaElements
        onLog("아르하 레이드 시작: ${seal.label} · ${elements.joinToString("+") { it.label }}")
        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }
        if (!waitForGameReady()) return

        publish("arha")
        if (!goToArha()) return

        var rounds = 0
        var quiet = 0
        while (rounds < 400 && !LoopStatus.stopRequested && abortReason == null) {
            val f = frame() ?: run { delay(1500); return@run null } ?: continue

            when {
                Judge.isArhaRaid(f) -> {
                    onLog("★ 레이드 출현 — 20자리를 채워야 한다. 여기서 멈춘다")
                    dumpFrame("arha-raid")
                    reachedTarget = true
                    LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
                    FloatingService.refresh(ctx)
                    return
                }
                Judge.isHungerPopup(f) -> {
                    onLog("  배고픔 — 음식을 먹인다")
                    tap(Judge.hungerOk); delay(3500); quiet = 0
                }
                Judge.isArhaCalc(f) -> { solveArhaCalc(f); quiet = 0 }
                Judge.isArhaAutoDone(f) -> {
                    tap(ArhaMap.autoDoneOk); delay(3000); quiet = 0
                }
                Judge.isArhaParty(f) -> {
                    onLog("  편성 — ${elements.joinToString("+") { it.label }}")
                    for (e in elements) {
                        tap(e.at); delay(2500)
                        tap(ArhaMap.partyCard1); delay(2500)
                    }
                    tap(ArhaMap.partyDone); delay(3500); quiet = 0
                }
                Judge.arhaFightAt(f) != null -> {
                    tap(ArhaMap.fight); delay(5000); quiet = 0
                }
                Judge.isArhaSeal(f) -> {
                    rounds++
                    onLog("  탐험 $rounds")
                    publish("arha")
                    tap(ArhaMap.explore); delay(5000); quiet = 0
                }
                else -> {
                    // 대사·연출은 눌러서 넘긴다. 지도로 돌아왔으면 봉인지를 다시 연다.
                    if (++quiet > 25) {
                        tap(seal.at); delay(3500); quiet = 0
                    } else {
                        tap(ArhaMap.nextTap); delay(1300)
                    }
                }
            }
        }
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "idle"))
        FloatingService.refresh(ctx)
    }

    /**
     * 시작할 때 아르하 가방을 먼저 본다.
     *
     * 어디부터 돌지는 지금 무엇이 얼마나 있는지에 달렸다. 그걸 모르고
     * 순서대로 돌면 이미 다 찬 봉인지에서 시간을 버린다.
     *
     * 가방은 **매주 수요일 12시에 초기화된다.** 그래서 우리가 세어둔 수를
     * 믿으면 안 되고 화면에 찍힌 것을 봐야 한다.
     *
     * ⬜ 개수를 숫자로 읽는 것은 아직 안 붙였다. 지금은 화면을 남겨
     *    사람이 볼 수 있게만 한다. 판독이 붙으면 여기서 건너뛸 봉인지를 고른다.
     */
    private suspend fun checkArhaBag() {
        onLog("  가방을 먼저 본다")
        tap(ArhaMap.bag); delay(4000)
        val f = frame()
        if (f == null || Judge.isArhaSeal(f)) {
            // 봉인지 창이 열렸다면 가방이 아니라 봉인지를 누른 것이다
            onLog("    ! 가방이 안 열렸다 — 건너뛴다")
            frame()?.let { if (Judge.isArhaSeal(it)) { tap(ArhaMap.closeX); delay(2500) } }
            return
        }
        dumpFrame("arha-bag-gem")
        tap(ArhaMap.bagTabEtc); delay(3000)
        dumpFrame("arha-bag-etc")
        onLog("    가방 화면을 남겼다 (젬 · ETC)")
        tap(ArhaMap.bagClose); delay(3000)
    }

    private enum class ArhaResult { DONE, RAID, FAILED }

    /**
     * 아르하 지도까지 간다.
     *
     * 이게 없으면 다른 곳에서 시작했을 때 봉인지 좌표가 엉뚱한 데를 누른다.
     * 월드맵의 아르하는 **누르면 입구가 둘로 펼쳐진다** — 한 번 더 눌러야 들어간다.
     * (그 자리가 설정 아이콘과 37px 차이라, 반대로 메뉴가 안 열린 채 설정을
     *  누르면 여기로 들어와 버린다. 그건 openSettings 에서 막는다.)
     */
    private suspend fun goToArha(): Boolean {
        repeat(6) {
            val f = frame() ?: run { delay(2000); return@repeat }
            if (Judge.isArhaSeal(f) || Judge.isArhaAutoDone(f)) return true

            // 아르하 지도를 눈으로 가려내려다 마을과 계속 헷갈렸다 (이름표도
            // 배경도 겹친다). 그림으로 재지 말고 **눌러서 확인한다** —
            // 봉인지를 눌러 창이 뜨면 아르하에 있는 것이다. 창이 안 뜨면
            // 아니니 월드맵부터 다시 간다.
            tap(ArhaMap.Seal.LIGHT.at); delay(3500)
            frame()?.let { if (Judge.isArhaSeal(it)) { onLog("  아르하 도착"); return true } }

            if (!goToWorldMap()) return false
            delay(1500)
            onLog("  아르하로 들어간다")
            tap(ArhaMap.worldArha); delay(3000)
            tap(ArhaMap.arhaEnter); delay(5000)
        }
        abort("아르하까지 가지 못했다")
        return false
    }

    /** 한 봉인지를 정해진 시간만큼 자동 탐험으로 돈다. */
    private suspend fun arhaOneSeal(seal: ArhaMap.Seal): ArhaResult {
        if (!startAuto(seal)) return ArhaResult.FAILED

        val end = System.currentTimeMillis() + ARHA_MINUTES * 60_000L
        var miss = 0
        while (System.currentTimeMillis() < end &&
            !LoopStatus.stopRequested && abortReason == null
        ) {
            delay(20_000)
            val f = frame() ?: continue

            if (Judge.isArhaRaid(f)) return ArhaResult.RAID
            if (autoRunning(f)) { miss = 0; continue }

            // 전투 연출이 화면을 덮으면 판정이 잠깐 흔들린다. 한 번 놓쳤다고
            // 멈춘 것으로 단정하면 멀쩡히 돌던 것을 죽인다 — 실제로 그랬다.
            if (++miss < 3) continue
            miss = 0

            when {
                Judge.isArhaAutoDone(f) -> {
                    onLog("    자동 탐험 완료창")
                    tap(ArhaMap.autoDoneOk); delay(4000)
                }
                Judge.isHungerPopup(f) -> {
                    onLog("    배고픔 — 음식을 먹인다")
                    tap(Judge.hungerOk); delay(3500)
                }
                Judge.isArhaCalc(f) -> solveArhaCalc(f)
            }
            if (!startAuto(seal)) return ArhaResult.FAILED
        }

        onLog("    ${ARHA_MINUTES}분 경과 — 자동 탐험을 끈다")
        tap(ArhaMap.autoStop); delay(5000)
        frame()?.let { if (Judge.isArhaAutoDone(it)) { tap(ArhaMap.autoDoneOk); delay(3000) } }
        return ArhaResult.DONE
    }

    /**
     * 자동 탐험이 도는 중인가.
     *
     * AUTO 마크를 찾는 방식으로 하다가 계속 틀렸다. 전투 섬광이 화면을 덮으면
     * 그 마크가 하얗게 날아가서, 멀쩡히 돌고 있는데 멈췄다고 판단했다.
     * 그래서 뒤집었다 — **멈췄을 때 뜨는 화면들을 알아보고, 그중 아무것도
     * 아니면 돌고 있는 것으로 본다.** 그 화면들은 정적이라 훨씬 잘 잡힌다.
     */
    private fun autoRunning(f: Bitmap): Boolean = !(
        Judge.isArhaAutoDone(f) || Judge.isArhaSeal(f) || Judge.isWorldMap(f) ||
            Judge.isHungerPopup(f) || Judge.isArhaCalc(f) || Judge.isArhaRaid(f)
        )

    /** 봉인지를 열고 자동 탐험을 켠다. 이미 돌고 있으면 그대로 둔다. */
    private suspend fun startAuto(seal: ArhaMap.Seal): Boolean {
        repeat(10) {
            val f = frame() ?: run { return@repeat }
            when {
                Judge.isArhaAutoDone(f) -> { tap(ArhaMap.autoDoneOk); delay(4000) }
                // 이미 돌고 있는데 다시 켜려다 실패한 적이 있다. 전투 중이라
                // 봉인지 창도 지도도 아니어서 헛누르고 포기했다.
                autoRunning(f) -> return true
                Judge.isArhaSeal(f) -> {
                    onLog("    자동 탐험 켬")
                    tap(ArhaMap.autoExplore); delay(3000)
                    tap(ArhaMap.autoStartOk); delay(6000)
                    return true
                }
                Judge.isHungerPopup(f) -> { tap(Judge.hungerOk); delay(3000) }
                else -> { tap(seal.at); delay(4000) }
            }
        }
        return false
    }

    /**
     * 탐험 중 계산 이벤트. `30 + 65 = ?` 를 풀어 숫자판으로 넣는다.
     * **숫자판 배치가 매번 섞이므로** 어느 칸에 어느 숫자가 있는지 매번 읽는다.
     */
    private suspend fun solveArhaCalc(f: Bitmap) {
        val eq = Judge.arhaEquation(f)
        val pad = Judge.arhaKeypad(f)
        if (eq == null || pad == null) {
            onLog("    ! 계산 화면을 못 읽었다 — 찍지 않는다")
            dumpFrame("arha-calc")
            return
        }
        val (a, op, b) = eq
        val v = if (op == '+') a + b else a - b
        onLog("    계산 $a $op $b = $v")
        for (ch in v.toString()) {
            pad[ch - '0']?.let { tap(it); delay(1000) }
        }
        tap(Judge.ARHA_CALC_OK); delay(3500)
    }

    /** 한 봉인지에 머무는 시간. 아이템 개수 판독이 붙기 전까지의 기준이다. */
    private val ARHA_MINUTES = 10

    // ---------------- 미션 루프 ----------------

    private val tMenuFriend = 1963 to 400
    private val tMenuAttend = 1830 to 400
    private val tMenuQuest = 1712 to 281
    /** 메뉴 마지막 줄의 FREE. 하루 세 번 무료 보상을 준다. */
    private val tMenuFree = 1828 to 679
    /** 퀘스트 창의 '모두받기'. */
    private val tQuestClaimAll = 1740 to 955
    /**
     * 월드맵 하늘 왕국 옆 보물상자. 실측 중심 (1188,262).
     * 처음에 (1200,250) 으로 눌렀다가 빗나가 '아직 시간이 안 됐다' 로 넘어갔다.
     * 상자 아래 타이머는 다음 상자까지의 시간이지 못 받는다는 뜻이 아니다.
     */
    private val tTreasure = 1188 to 262

    /**
     * 하루치 미션을 돈다.
     *
     * 한 번 돌면 끝나는 루프다. 오늘 이미 한 것은 기록을 보고 건너뛴다.
     * 각 단계는 실패해도 다음 단계로 넘어간다 — 하나가 막혔다고 나머지까지
     * 못 하면 손해다. 무엇을 했고 무엇을 못 했는지는 로그에 남는다.
     */
    private suspend fun runMission() {
        onLog("미션 루프 시작")
        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }

        val mp = MissionPrefs(ctx)
        if (!waitForGameReady()) return

        publish("mission")
        friendGifts(mp)
        attendance(mp)
        treasureBox(mp)
        freeRewards(mp)
        missionExplore(mp)
        // 재화를 쓰는 것들은 뒤에 둔다. 앞의 공짜 미션이 먼저 끝나 있어야
        // 중간에 막혀도 손해가 적다.
        capsuleArena(mp)
        challengeArena(mp)
        tournament(mp)
        claimQuests(mp)

        onLog("미션 루프 끝")
        // 미션은 목표 레벨이 없다. 끝까지 걸어온 것 자체가 완료다.
        // 이걸 세워야 서비스가 완료 알림을 울린다.
        reachedTarget = abortReason == null
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
        FloatingService.refresh(ctx)
    }

    /**
     * 메뉴를 열고 항목 하나를 누른다. 메뉴가 안 열리면 false.
     *
     * 예전엔 상단 아이콘 줄이 보이는지로 확인했는데, 그건 메뉴와 상관없이
     * 지도에서 늘 보인다. 즉 확인이 아니었다. 메뉴가 안 열린 채 항목 좌표를
     * 누르면 그 탭이 지도로 떨어지고, 엉뚱한 지역으로 들어가거나 아무 일도
     * 안 일어난다. 출석체크 · 보물상자 · 전장이 줄줄이 실패한 게 전부 이것 때문이었다.
     *
     * 메뉴 버튼은 토글이라 한 번 더 누르면 닫힌다. 그리고 용 패널이 열려 있으면
     * 첫 탭이 패널 닫기에 먹힌다. 그래서 열렸는지 보고 안 열렸을 때만 다시 누른다.
     */
    private suspend fun openMenuItem(at: Pair<Int, Int>, name: String): Boolean {
        if (!goToWorldMap()) return false
        for (attempt in 1..3) {
            if (frame()?.let { Judge.isMenuOpen(it) } == true) {
                tap(at); delay(5000)
                return true
            }
            onLog("  메뉴 여는 중 ($attempt/3)")
            tap(tMenu); delay(2500)
        }
        onLog("  ! 메뉴를 열지 못했다 ($name)")
        dumpFrame("menu-$name")
        return false
    }

    /**
     * 친구에게 정기 보내고 받기. 일일 퀘스트 두 개가 여기서 끝난다.
     *
     * 보내기는 속성을 골라야 한다. 요일마다 다른 것을 보낸다 —
     * 같은 것만 계속 보내면 받는 쪽에 쓸모가 없다.
     */
    private suspend fun friendGifts(mp: MissionPrefs) {
        if (mp.done(MissionPrefs.GIFT_SEND) && mp.done(MissionPrefs.GIFT_RECV)) {
            onLog("  정기 주고받기 — 오늘 이미 함"); return
        }
        if (!openMenuItem(tMenuFriend, "친구")) return

        val bar = frame()?.let { Judge.friendToolbar(it) } ?: run {
            onLog("  ! 친구 창을 확인하지 못했다 — 아무것도 누르지 않는다"); return
        }
        val (sendX, recvX) = bar
        onLog("  친구 창 — 전부 보내기 x=$sendX · 받기 x=$recvX")

        if (!mp.done(MissionPrefs.GIFT_SEND)) {
            tap(sendX to 70); delay(3000)
            val g = frame()
            if (g != null && Judge.isGiftPicker(g)) {
                val slot = mp.giftSlotForToday()
                onLog("  정기 보내기 — 오늘은 ${MissionPrefs.GIFT_NAMES[slot]}")
                tap(Judge.giftSlots[slot] to Judge.GIFT_SLOT_Y); delay(1500)
                if (tapConfirm("정기 선택")) {
                    delay(3000)
                    tapConfirm("보내기 결과")
                    mp.markDone(MissionPrefs.GIFT_SEND)
                    mp.sentSlot = slot
                }
            } else {
                onLog("  ! 정기 선택 창이 안 떴다 — 건너뛴다")
            }
        }

        if (!mp.done(MissionPrefs.GIFT_RECV)) {
            delay(1500)
            tap(recvX to 70); delay(3000)
            // 받을 게 있으면 보상창, 없으면 '받을 아이템이 없습니다'.
            // 어느 쪽이든 파란 확인 하나를 누르면 된다. 두 번까지 본다.
            var closed = 0
            repeat(2) { if (tapConfirm("받기 결과")) { closed++; delay(2500) } }
            if (closed > 0) mp.markDone(MissionPrefs.GIFT_RECV)
            onLog("  정기 받기 — 확인 $closed 회")
        }

        tap(tWindowX); delay(3000)
    }

    /** 출석체크. 창을 열면 오늘치가 찍힌다. 파란 확인만 누른다. */
    private suspend fun attendance(mp: MissionPrefs) {
        if (mp.done(MissionPrefs.ATTENDANCE)) { onLog("  출석체크 — 오늘 이미 함"); return }
        if (!openMenuItem(tMenuAttend, "출석체크")) return

        // 옆의 초록 '드빌.net 출석' 은 웹으로 나간다. 절대 누르지 않는다.
        // 파란 확인만 찾아 누른다.
        if (tapConfirm("출석체크")) {
            mp.markDone(MissionPrefs.ATTENDANCE)
            onLog("  출석체크 완료")
        } else {
            // 왜 못 찾았는지는 화면을 봐야 안다. 루프가 지나가면 그 화면은 사라진다.
            onLog("  ! 출석체크 확인 버튼을 찾지 못했다")
            dumpFrame("attendance")
            tap(tWindowX); delay(2500)
        }
    }

    /**
     * 월드맵의 보물상자.
     *
     * 누르면 '보물상자 보상' 창이 뜨고 보상은 우편함으로 간다.
     * 상자 아래 타이머는 다음 상자까지의 시간이라 지금 못 받는다는 뜻이 아니다.
     *
     * 받을 게 없으면 창이 안 뜬다. 그때는 그냥 넘어간다.
     */
    private suspend fun treasureBox(mp: MissionPrefs) {
        if (mp.done(MissionPrefs.TREASURE)) { onLog("  보물상자 — 오늘 이미 받음"); return }
        if (!goToWorldMap()) return

        tap(tTreasure); delay(4000)
        if (tapConfirm("보물상자 보상")) {
            mp.markDone(MissionPrefs.TREASURE)
            onLog("  보물상자 수령 (보상은 우편함으로)")
        } else {
            onLog("  보물상자 — 받을 것이 없다")
        }
    }

    // 마을 · 전장 좌표 (전부 실측)
    private val tVillage = 861 to 798            // 월드맵 → 마을
    private val tBattleground = 1596 to 190      // 마을 → 드래곤 전장
    private val tCapsuleArena = 918 to 520       // 캡슐드래곤 전장
    private val tChallengeArena = 1420 to 520    // 도전의 전장
    private val tColosseum = 1237 to 330         // 마을 → 콜로세움
    private val tTournament = 1385 to 480        // 콜로세움 → TOURNAMENT
    private val tGeneral = 705 to 480            // 토너먼트 → GENERAL
    private val tArenaFight = 620 to 500         // CAPSULE ARENA 의 FIGHT! (실측 600,480)
    private val tSkip = 1167 to 193              // 전투 SKIP (실측)
    private val tPartyCards = listOf(505 to 450, 1010 to 450, 1490 to 450, 1970 to 450)

    /** 마을까지 간다. 전장·콜로세움이 모두 여기 있다. */
    private suspend fun goToVillage(): Boolean {
        if (!goToWorldMap()) return false
        tap(tVillage); delay(6000)
        val f = frame()
        if (f == null || !Judge.isGameTopBarVisible(f)) {
            onLog("  ! 마을로 가지 못했다"); return false
        }
        return true
    }

    /**
     * 캡슐드래곤 전장 참가.
     *
     * 들어가면 지난번 등록분 전투가 먼저 돌아간다. 그게 끝나야 다시 등록할 수 있고,
     * 등록을 해야 미션이 인정된다. 참가에 다이아 3개가 든다 — 게임이 정한 조건이다.
     */
    private suspend fun capsuleArena(mp: MissionPrefs) {
        if (mp.done(MissionPrefs.CAPSULE_ARENA)) { onLog("  캡슐 전장 — 오늘 이미 참가"); return }
        if (!goToVillage()) return
        tap(tBattleground); delay(5000)
        tap(tCapsuleArena); delay(8000)

        // 흐름이 세 가지뿐이다. 무엇을 보고 무엇을 했는지 매번 남긴다 —
        // 지난번에는 아무 로그도 없이 조용히 빠져나가 실패한 줄도 몰랐다.
        //   전투 결과창(빨간 확인)  → 넘긴다
        //   골드카드 창(파란 확인)   → 누르면 등록 완료 (다이아 3개)
        //   그 밖                  → FIGHT! 을 눌러 등록을 시도한다
        var round = 0
        var registered = false
        while (round < 40 && abortReason == null && !LoopStatus.stopRequested) {
            round++
            val f = frame() ?: run { onLog("  ! 캡슐 전장 화면을 읽지 못했다"); return }

            val red = Judge.redConfirmButton(f)
            if (red != null) {
                onLog("    이전 전투 결과 확인 (${red.first},${red.second})")
                tap(red); delay(3000)
                continue
            }
            val blue = Judge.confirmButton(f)
            if (blue != null) {
                onLog("    골드카드 확인 (${blue.first},${blue.second}) — 다이아 3개")
                tap(blue); delay(4000)
                registered = true
                break
            }
            if (round % 4 == 1) onLog("    캡슐 전장 대기 ($round/40)")
            tap(tArenaFight); delay(3000)
        }

        if (registered) {
            mp.markDone(MissionPrefs.CAPSULE_ARENA)
            onLog("  캡슐 전장 등록 완료")
        } else {
            onLog("  ! 캡슐 전장 등록을 못 했다 ($round 번 시도)")
            dumpFrame("capsule")
        }
        tap(tWindowX); delay(3000)
        tap(tWindowX); delay(3000)
    }

    /**
     * 도전의 전장. 붉은 칼인 상대만 칠 수 있다 — 회색은 이미 이긴 상대다.
     *
     * 붉은 칼을 누르면 ATTACK PARTY 편성 화면이 뜬다. 넷을 골라야 싸울 수 있다.
     *
     * **결과창을 본 것만 한 번으로 센다.** 예전에는 누르고 나서 무조건 세어,
     * 실제로는 안 싸웠는데 10/10 이 되어 있었다.
     */
    private suspend fun challengeArena(mp: MissionPrefs) {
        var done = mp.count(MissionPrefs.CHALLENGE)
        if (done >= MissionPrefs.CHALLENGE_MAX) { onLog("  도전의 전장 — 오늘 다 함"); return }
        if (!goToVillage()) return
        tap(tBattleground); delay(5000)
        tap(tChallengeArena); delay(8000)

        while (done < MissionPrefs.CHALLENGE_MAX && abortReason == null &&
            !LoopStatus.stopRequested
        ) {
            var f = frame() ?: break
            var targets = Judge.arenaRedSwords(f)
            if (targets.isEmpty()) {
                // 아래에 더 있을 수 있다
                a11y?.swipe(1500, 800, 1500, 400); delay(3000)
                f = frame() ?: break
                targets = Judge.arenaRedSwords(f)
            }
            if (targets.isEmpty()) { onLog("  도전의 전장 — 칠 상대가 없다"); break }

            tap(1914 to targets.first()); delay(5000)

            // 편성 화면인지 보고 나서 카드를 누른다. 아니면 이 탭들이 전부
            // 대전목록으로 떨어져 엉뚱한 상대가 열린다.
            if (!waitFor(8000) { Judge.isAttackParty(it) }) {
                onLog("  ! 편성 화면이 안 떴다 — 아무것도 누르지 않는다")
                dumpFrame("arena-party")
                break
            }
            tPartyCards.forEach { tap(it); delay(1200) }

            // 선택완료 좌표는 재본 적이 없다. 추측한 자리를 누르면 편성 화면의
            // 붉은 배너를 누르게 된다. 화면에서 버튼을 찾고, 못 찾으면 멈춘다.
            val doneBtn = frame()?.let { Judge.confirmButton(it) ?: Judge.redConfirmButton(it) }
            if (doneBtn == null) {
                onLog("  ! 선택완료 버튼을 못 찾았다 — 화면을 남기고 멈춘다")
                dumpFrame("arena-done")
                break
            }
            onLog("    선택완료 (${doneBtn.first},${doneBtn.second})")
            tap(doneBtn); delay(6000)
            tap(tSkip); delay(4000)

            // 결과창을 본 것만 한 번으로 센다. 예전에는 누르고 나서 무조건 세어,
            // 실제로는 안 싸웠는데 10/10 이 되어 있었다.
            val r = frame()?.let { Judge.redConfirmButton(it) }
            if (r == null) {
                onLog("  ! 결과창이 안 떴다 — 세지 않고 멈춘다")
                dumpFrame("arena-result")
                break
            }
            tap(r); delay(4000)
            mp.bump(MissionPrefs.CHALLENGE)
            done = mp.count(MissionPrefs.CHALLENGE)
            onLog("  도전의 전장 $done/${MissionPrefs.CHALLENGE_MAX}")
            publish("mission")
        }
        tap(tWindowX); delay(3000)
        tap(tWindowX); delay(3000)
    }

    /**
     * 콜로세움 GENERAL 토너먼트 참가. 티켓 1개가 든다.
     * 참가만 하면 미션이 인정되므로 결과는 보지 않고 나온다.
     */
    private suspend fun tournament(mp: MissionPrefs) {
        if (mp.done(MissionPrefs.TOURNAMENT)) { onLog("  토너먼트 — 오늘 이미 참가"); return }
        if (!goToVillage()) return
        tap(tColosseum); delay(7000)
        tap(tTournament); delay(6000)
        tap(tGeneral); delay(5000)

        if (tapConfirm("토너먼트 참가")) {
            onLog("  토너먼트 참가 (티켓 1개)")
            mp.markDone(MissionPrefs.TOURNAMENT)
        } else {
            onLog("  ! 토너먼트 참가 창이 안 떴다")
        }
        delay(4000)
        tap(tWindowX); delay(3000)
        tap(tWindowX); delay(3000)
    }

    /**
     * 메뉴의 FREE — 무료 보상. 하루 세 번이다.
     *
     * '받으시겠습니까' 창의 확인을 누르면 보상창이 뜨고, 그 확인을 또 눌러야 한다.
     * 확인 두 번이 한 세트다. 세 번 다 받으면 '무료 보상 헌터' 가 끝난다.
     */
    private suspend fun freeRewards(mp: MissionPrefs) {
        var got = mp.count(MissionPrefs.FREE_REWARD)
        if (got >= MissionPrefs.FREE_REWARD_MAX) {
            onLog("  무료 보상 — 오늘 세 번 다 받음"); return
        }
        while (got < MissionPrefs.FREE_REWARD_MAX && abortReason == null) {
            if (!openMenuItem(tMenuFree, "FREE")) return
            if (!tapConfirm("무료 보상 수령")) {
                onLog("  무료 보상 — 더 받을 것이 없다"); return
            }
            delay(3000)
            tapConfirm("무료 보상 결과")
            mp.bump(MissionPrefs.FREE_REWARD)
            got = mp.count(MissionPrefs.FREE_REWARD)
            onLog("  무료 보상 $got/${MissionPrefs.FREE_REWARD_MAX}")
        }
    }

    /**
     * 미션용 탐험 10회.
     *
     * 반드시 일반 각성 45렙으로 돈다. 키우는 중인 용으로 돌리면 원치 않는 경험치가
     * 들어가 등급이 흔들린다. 지역은 가장 짧은 희망의 숲이다 — 목적이 횟수 채우기다.
     */
    private suspend fun missionExplore(mp: MissionPrefs) {
        var done = mp.count(MissionPrefs.EXPLORE10)
        if (done >= MissionPrefs.EXPLORE_MAX) {
            onLog("  탐험 미션 — 오늘 열 번 다 함"); return
        }
        if (!pickMissionDragon()) return
        if (!leaveCave()) return

        val zone = ExploreMap.zones.first()      // 희망의 숲
        while (done < MissionPrefs.EXPLORE_MAX && abortReason == null &&
            !LoopStatus.stopRequested
        ) {
            if (!openZoneDialog(zone)) return
            if (runOneExploration() == null) return
            mp.bump(MissionPrefs.EXPLORE10)
            done = mp.count(MissionPrefs.EXPLORE10)
            onLog("  탐험 $done/${MissionPrefs.EXPLORE_MAX}")
            publish("mission")
        }
    }

    /**
     * 퀘스트 창의 '모두받기'. 끝난 퀘스트의 보상을 한 번에 받는다.
     *
     * 보상창이 여러 번 이어질 수 있고, 뜨는 데 시간이 걸린다.
     * 지난번에는 곧바로 확인을 찾다가 '확인 0 회' 로 아무것도 못 받았다.
     */
    private suspend fun claimQuests(mp: MissionPrefs) {
        if (!openMenuItem(tMenuQuest, "퀘스트")) return

        onLog("    모두받기 (${tQuestClaimAll.first},${tQuestClaimAll.second})")
        tap(tQuestClaimAll); delay(4000)

        var closed = 0
        repeat(6) {
            // 보상창이 늦게 뜨는 일이 있어 잠깐 기다렸다 본다
            var at: Pair<Int, Int>? = null
            repeat(4) {
                at = frame()?.let { f -> Judge.confirmButton(f) }
                if (at != null) return@repeat
                delay(900)
            }
            val p = at ?: return@repeat
            onLog("    퀘스트 보상 확인 (${p.first},${p.second})")
            tap(p); delay(2200)
            closed++
        }
        if (closed > 0) {
            mp.markDone(MissionPrefs.QUEST_CLAIM)
            onLog("  퀘스트 모두받기 — 보상 $closed 건")
        } else {
            onLog("  퀘스트 모두받기 — 받을 보상이 없었다")
            dumpFrame("claim")
        }
        tap(tWindowX); delay(2500)
    }

    /**
     * 모달의 파란 확인을 누른다. 없으면 false.
     *
     * 결과창은 버튼이 하나일 때도 둘일 때도 있는데 파란 것은 언제나 확인이다.
     * 취소는 회색이라 걸리지 않는다.
     */
    private suspend fun tapConfirm(what: String): Boolean {
        val f = frame() ?: return false
        val at = Judge.confirmButton(f) ?: return false
        onLog("    $what 확인 (${at.first},${at.second})")
        tap(at); delay(2500)
        return true
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

            tap(z.at); delay(3000)
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
        tap(tExploreBtn); delay(3500)

        sawLevelUp = false
        readLevel = null
        readGrade = null
        var taps = 0
        while (taps < 90 && !LoopStatus.stopRequested) {
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
                tap(Judge.levelUpOk); delay(2500)
                taps++
                continue
            }

            val fy = Judge.fightButtonY(f)
            if (fy != null) {
                onLog("    몬스터 — 싸운다")
                tap(tFightX to fy); delay(3500)
                taps++
                continue
            }

            // 대사 넘기기. 화면 한가운데는 지도로 돌아온 뒤 누르면 위험해서
            // 대사 상자 자리를 누른다. 지도 판정이 먼저 걸러주지만 한 겹 더 둔다.
            // 매 번 화면을 확인하므로 짧게 눌러도 앞서가지 않는다.
            tap(tExploreNext); delay(1100)
            taps++
        }
        if (LoopStatus.stopRequested) return null
        abort("탐험이 끝나지 않는다 ($taps 번 넘겼다)")
        return null
    }

    /** 연속 실패 수. 성공하면 0으로 돌아간다. */
    private var fails = 0

    /** 결과창에서 마지막으로 읽은 등급. 못 읽었으면 null. */
    private var lastGrade: Double? = null

    /** 프리미엄 레벨업을 쓰는 용인가. 각성 용은 각성 Lv+1 을 써서 해당 없다. */
    private val usesPremium = setup.stage?.awakened != true

    /**
     * 실패 처리. 계속 돌릴 수 있으면 true.
     *
     * 프리미엄을 쓰는 용은 각성 용보다 훨씬 안 뜬다. 그래서 실패가 쌓이면
     * 방법을 바꾼다 — **10회마다 마이너스로 한 칸 내렸다가 다시 올리고,
     * 30회마다 스페셜을 쓴다.** 그 외에는 늘 하던 대로 데삭으로 되돌린다.
     *
     * 30회는 10의 배수이기도 하다. 겹칠 때는 더 센 쪽인 스페셜을 쓴다 —
     * 둘 다 하면 한 번의 실패로 아이템을 셋이나 태운다.
     */
    private suspend fun handleFailure(): Boolean {
        fails++

        // 등급이 하한 아래로 내려갔으면 **되돌리는 것이 먼저다.**
        // 그 상태에서 다시 올리면 이미 내려간 등급 위에 한 칸을 더 얹는 셈이라
        // 아무리 좋은 아이템을 써도 기준으로 돌아오지 않는다.
        // 지켜야 할 것은 '그 등급 아래로 안 내려가는 것' 하나다.
        val g = lastGrade
        if (usesPremium && g != null && g < setup.minGrade - 0.001) {
            val special = fails % 30 == 0
            onLog(
                "  등급 ${String.format("%.1f", g)} < 하한 " +
                    "${String.format("%.1f", setup.minGrade)} — 마이너스로 되돌린다" +
                    if (special) " (다음은 스페셜)" else ""
            )
            publish("levelup")
            if (!doLevelDown()) return false
            if (frame()?.let { Judge.isLevelUpDialog(it) } != true) {
                onLog("    마이너스 뒤 레벨업 창이 아니다 — 다시 연다")
                if (!openDialog()) return false
            }
            lastGrade = null   // 되돌린 뒤 등급은 다음 결과창에서 다시 읽는다
            return selectUpItem(
                if (special) Judge.UpItem.SPECIAL else Judge.UpItem.PREMIUM,
            )
        }

        if (usesPremium && fails % 10 == 0) {
            // 30회는 10의 배수이기도 하다. 겹칠 때는 더 센 스페셜을 쓴다.
            val special = fails % 30 == 0
            val itemName = if (special) "스페셜" else "프리미엄"

            // **먼저 되돌린다.** 방금 실패한 레벨업 때문에 등급이 지켜야 할
            // 하한 아래로 내려가 있다. 그 상태에서 아이템을 쓰면 이미 망가진
            // 등급 위에 한 칸을 더 얹는 셈이라 아무 의미가 없다.
            // 예전에는 30회 분기가 되돌리지도 않고 곧바로 스페셜을 물렸다 —
            // 6.5 가 아닌 상태에서 스페셜을 태웠다.
            onLog("  연속 실패 $fails 회 — 마이너스로 되돌리고 ${itemName}으로 간다")
            publish("levelup")
            if (!doLevelDown()) return false

            // 내린 다음의 올리기는 이어지는 사이클이 한다. 여기서는 레벨업
            // 창으로 돌아와 있는지만 확인하면 된다. 결과창이 덮은 채로 넘기면
            // 다음 사이클이 '레벨업 다이얼로그가 아니다' 로 멈춘다.
            if (frame()?.let { Judge.isLevelUpDialog(it) } != true) {
                onLog("    마이너스 뒤 레벨업 창이 아니다 — 다시 연다")
                if (!openDialog()) return false
            }
            return selectUpItem(
                if (special) Judge.UpItem.SPECIAL else Judge.UpItem.PREMIUM,
            )
        }
        publish("desak")
        if (!desak()) return false
        // 즐겨찾기는 서버에 남아 데삭 후에도 유지된다. 대상 지정을 다시 할 필요가 없다.
        if (!openDialog()) return false
        // 데삭은 아이템 선택도 초기화한다. 스페셜이 물린 채로 넘어가지 않게 되돌린다.
        if (usesPremium && !selectUpItem(Judge.UpItem.PREMIUM)) return false
        return true
    }

    /**
     * 업 아이템을 원하는 것으로 물린다. 이미 그것이면 아무것도 안 한다.
     *
     * 사용 아이템 칸을 누르면 '아이템 선택' 창이 뜬다. 거기서 고른 뒤
     * **칸의 아이콘 색으로 실제로 바뀌었는지 확인한다.** 확인창에는 아이콘이
     * 없고 제목 글자만 다른데, 프리미엄과 스페셜은 폭이 비슷해 흰 점이 10%
     * 밖에 차이 안 난다. 그걸로 가르면 언젠가 엉뚱한 것을 쓴다.
     */
    private suspend fun selectUpItem(want: Judge.UpItem): Boolean {
        val now = frame()?.let { Judge.upItem(it) }
        if (now == want) return true

        tap(tItemSlotUp); delay(2500)
        if (!waitFor(6000) { Judge.isItemPicker(it) }) {
            onLog("    ! 아이템 선택 창이 안 떴다")
            dumpFrame("item-picker")
            return false
        }
        tap(if (want == Judge.UpItem.SPECIAL) tPickSpecial else tPickPremium)
        delay(2500)

        val after = frame()?.let { Judge.upItem(it) }
        if (after != want) {
            onLog("    ! 아이템이 안 바뀌었다 (원한 것 $want, 지금 $after)")
            dumpFrame("item-select")
            return false
        }
        onLog("    아이템: ${if (want == Judge.UpItem.SPECIAL) "스페셜" else "프리미엄"} 레벨업")
        return true
    }

    /**
     * 마이너스(Lv - 1) 1회.
     *
     * 레벨을 한 칸 내리면 그 레벨의 굴림이 다시 열린다. 실패가 쌓였을 때
     * 내렸다가 다시 올리기 위한 것이다. 게임 설명에 '현재 레벨 이전
     * 레벨까지만 사용할 수 있다' 고 되어 있어 한 칸씩만 내려간다.
     *
     * 확인창이 정말 `Lv - 1` 인지 보고 누른다. 올리려다 내리는 것보다
     * 내리려다 못 내리는 쪽이 낫다.
     */
    private suspend fun doLevelDown(): Boolean {
        tap(tLevelDown); delay(2500)
        val f = frame() ?: run { onLog("    ! 화면을 읽지 못했다"); return false }
        if (!Judge.isMinusDialog(f)) {
            onLog("    ! 마이너스 확인창이 아니다 — 취소한다")
            dumpFrame("minus-dialog")
            tap(tItemCancel); delay(2000)
            return false
        }
        onLog("    마이너스 사용 (Lv -1)")
        tap(tItemOk); delay(4000)

        // 레벨업과 마찬가지로 결과창이 뜬다. 그 확인을 안 누르면 창이 덮은 채로
        // 다음 단계가 굴러가고, 이어지는 아이템 선택이 그 위로 탭을 날린다.
        // 결과창은 늦게 뜰 수 있으니 기다렸다가 누른다.
        if (waitFor(12000) { Judge.isResultPopup(it) }) {
            val lv = frame()?.let { Judge.levelUpLevel(it) }
            onLog("    마이너스 결과 확인${if (lv != null) " (Lv.$lv)" else ""}")
            tap(tResultOk); delay(3000)
        } else {
            // 안 뜨는 경우도 있을 수 있다. 덮인 창이 없으면 그대로 진행한다.
            onLog("    마이너스 결과창 없음 — 그대로 진행")
        }
        return true
    }

    /** 레벨업 1회. true=트리플맥스, null=중단 */
    private suspend fun doCycle(): Boolean? {
        clearBlockers()   // 루프 도중에도 팝업·시스템 창은 언제든 끼어든다
        val f0 = frame() ?: run { abort("화면을 읽지 못했다"); return null }
        if (!Judge.isLevelUpDialog(f0)) { abort("사이클 시작 시 레벨업 다이얼로그가 아니다"); return null }

        tap(tLevelUp); delay(3000)
        // 확인창이 마이너스면 누르지 않는다. 레벨 업 자리를 눌렀는데 마이너스
        // 창이 떴다는 건 좌표가 어긋났다는 뜻이고, 그대로 확인하면 레벨이 내려간다.
        frame()?.let {
            if (Judge.isMinusDialog(it)) {
                onLog("  ! 레벨 업을 눌렀는데 마이너스 창이 떴다 — 취소한다")
                dumpFrame("wrong-dialog")
                tap(tItemCancel); delay(2000)
                abort("아이템 확인창이 예상과 다르다")
                return null
            }
        }
        tap(tItemOk); delay(4000)

        if (!waitFor(20000) { Judge.isResultPopup(it) }) {
            abort("레벨업 결과창이 안 뜬다"); return null
        }

        val f = frame() ?: run { abort("결과창을 읽지 못했다"); return null }
        val res = Judge.badges(f)
        val triple = res.values.all { it }
        val grade = Judge.gradeIs70(f)

        // 결과창에 찍힌 실제 등급을 읽는다.
        //
        // 예전에는 뱃지 3개만 보고 판정하고 로그에는 늘 '7.0' 을 찍었다.
        // 6.5 를 지키는 용인데도 7.0 이라고 적혀 무엇이 잘못됐는지 늦게 알았다.
        // 지켜야 할 기준은 설정에 들어온 값이고, 그 아래로 안 내려가면 된다.
        val (readNow, gradeRaw) = Judge.readGrade(f)
        if (readNow != null) lastGrade = readNow
        val floor = setup.minGrade
        val ok = readNow != null && readNow >= floor - 0.001

        // 등급을 읽었으면 그것으로 판정한다. 못 읽었을 때만 뱃지로 대신한다 —
        // 뱃지 3개면 등급이 그대로 유지되기 때문이다.
        val win = if (readNow != null) ok else triple

        val mark = res.entries.joinToString(" ") { "${it.key}${if (it.value) "O" else "X"}" }
        val gtxt = readNow?.let { String.format("%.1f", it) }
            ?: "등급판정불가${gradeRaw?.let { " ($it)" } ?: ""}"
        onLog(
            "사이클 $cycle: $mark / $gtxt " +
                "(하한 ${String.format("%.1f", floor)}) → ${if (win) "유지" else "하한 아래"}"
        )

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

    /**
     * 동굴에서 대상을 고른다. **모든 루프가 이 하나를 쓴다.**
     *
     * 전에는 레벨업·탐험용과 미션용이 따로 있었다. 같은 일을 두 벌로 만들어 두니
     * 한쪽을 고쳐도 다른 쪽은 그대로였다. 실제로 '검색이 안 된다' 는 문제가
     * 레벨업 루프에서 한 번, 미션 루프에서 또 한 번 났다.
     *
     * @param name    이름으로 검색할 때. null 이면 검색하지 않고 필터 결과에서 고른다
     * @param stage   단계 필터. null 이면 단계는 건드리지 않는다
     * @param level45 레벨 45 필터를 걸지
     * @param skipTrueAwakened 진각성(빨간 마크)을 건너뛸지
     */
    private suspend fun selectDragonInCave(
        name: String?,
        stage: Stage?,
        level45: Boolean = false,
        skipTrueAwakened: Boolean = false,
    ): Boolean {
        for (attempt in 1..3) {
            if (!ensureCaveGrid()) return false

            if (!applyFilters(stage, level45)) {
                onLog("    필터 실패 ($attempt/3) — 되돌리고 재시도")
                tap(tCaveX); delay(3000)
                continue
            }

            // 이름이 있으면 검색으로 좁힌다. 같은 이름이 단계별로 여러 마리라
            // 필터와 검색을 함께 써야 정확히 한 마리가 남는다.
            if (name != null) {
                tap(tSearch); delay(2500)
                if (!typeText(name)) {
                    onLog("    검색 진입 실패 ($attempt/3) — 되돌리고 재시도")
                    tap(tCaveX); delay(3000)
                    continue
                }
                delay(1500)
                // 입력이 됐을 때만 키보드를 내린다.
                // 키보드가 안 떠 있으면 이 뒤로가기가 게임 종료창을 띄운다.
                a11y?.back(); delay(2500)
            }

            val g = frame() ?: run { abort("목록을 읽지 못했다"); return false }
            val cell = pickCell(g, skipTrueAwakened)
            if (cell == null) {
                onLog("    고를 칸이 없다 ($attempt/3)")
                tap(tCaveX); delay(3000)
                continue
            }
            tap(cell to Judge.GRID_CELL_Y); delay(3000)
            tap(tGridClose); delay(3000)
            return true
        }
        abort("동굴에서 대상을 고르지 못했다")
        return false
    }

    /** 목록에서 고를 칸의 가로 위치. 진각성을 걸러야 하면 건너뛴다. */
    private fun pickCell(f: Bitmap, skipTrueAwakened: Boolean): Int? {
        if (!skipTrueAwakened) return Judge.gridCellX(0)
        for (i in 0 until 5) {
            val cx = Judge.gridCellX(i)
            when (Judge.isTrueAwakened(f, cx)) {
                false -> { onLog("    ${i + 1}번 칸 선택 (일반 각성)"); return cx }
                true -> onLog("    ${i + 1}번 칸은 진각성 — 건너뜀")
                null -> return null      // 빈 칸이면 그 뒤도 비어 있다
            }
        }
        return null
    }

    /** 단계·레벨 필터를 설정값 하나로만 맞춘다. 버튼이 토글이라 상태를 먼저 읽는다. */
    private suspend fun applyFilters(stage: Stage?, level45: Boolean): Boolean {
        if (stage == null && !level45) return true
        if (!openFilterPanel()) return false

        if (stage != null) {
            val f = frame() ?: return false
            if (stageButtons.any { (s, p) ->
                    s != stage && Judge.isFilterButtonOn(f, p.first, p.second)
                }
            ) { tap(tStageAll); delay(2000) }
            val want = stageButton(stage)
            if (frame()?.let { Judge.isFilterButtonOn(it, want.first, want.second) } != true) {
                tap(want); delay(2000)
            }
        }

        if (level45) {
            val f = frame() ?: return false
            if (levelButtons.any {
                    it != tLevel45 && Judge.isFilterButtonOn(f, it.first, it.second)
                }
            ) { tap(tLevelAll); delay(2000) }
            if (frame()?.let {
                    Judge.isFilterButtonOn(it, tLevel45.first, tLevel45.second)
                } != true
            ) { tap(tLevel45); delay(2000) }
        }

        val f = frame() ?: return false
        val stageOk = stage == null ||
            Judge.isFilterButtonOn(f, stageButton(stage).first, stageButton(stage).second)
        val levelOk = !level45 ||
            Judge.isFilterButtonOn(f, tLevel45.first, tLevel45.second)
        if (!stageOk || !levelOk) {
            onLog("    필터가 안 맞는다 (단계=$stageOk 레벨=$levelOk)")
            closeFilterPanel()
            return false
        }
        onLog(
            "    필터: " + listOfNotNull(stage?.label, if (level45) "레벨 45" else null)
                .joinToString(" + ")
        )
        return closeFilterPanel()
    }

    /**
     * 미션용 탐험 대상 — **일반 각성 45렙**.
     *
     * 키우는 중인 용으로 돌리면 원치 않는 경험치가 들어가 등급이 흔들린다.
     * 더 오를 데가 없는 45렙으로만 돌린다. 각성으로 맞추는 것은 캡슐 전장
     * 미션까지 같은 용을 쓰기 위해서다. 진각성은 겉모습으로 구분되지 않아
     * 칸 아래 마크로 걸러야 한다.
     */
    private suspend fun pickMissionDragon(): Boolean =
        selectDragonInCave(
            name = null,
            stage = Stage.AWAKENED,
            level45 = true,
            skipTrueAwakened = true,
        )

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
                onLog("    동굴 진입 확인 실패 ($round/20) — 되돌린다")
                tap(tWindowX); delay(4000)
            } else if (finishExploration(f)) {
                // 탐험이나 전투가 진행 중이었다. 그건 창을 닫아서 빠져나올 수 없다.
                // 재시작이 전투 중에 걸리면 표준 X 를 여섯 번 눌러도 화면이 그대로다.
                inExploration = true
            } else if (inExploration) {
                tap(tExploreNext); delay(2500)
            } else {
                // 설정창은 닫기 X 가 표준 자리가 아니라 패널 안쪽에 있다.
                // 계정삭제가 있는 창이라 아무 데나 누르면 안 된다 — 제 X 로만 닫는다.
                if (Judge.isSettingsOpen(f)) {
                    onLog("    설정창 닫기")
                    tap(tSetX); delay(3000)
                    continue
                }
                // 확인만 있는 창은 표준 X 로 안 닫힌다. '데이터를 저장했습니다' 가
                // 그렇다. 그걸 모르고 X 를 스무 번 누르다 멈춘 적이 있다.
                // 레벨업 다이얼로그는 건드리지 않는다 — 확인을 누르면 아이템을 쓴다.
                val ok = if (Judge.isLevelUpDialog(f)) null else Judge.confirmButton(f)
                if (ok != null) {
                    onLog("    확인 창 닫기 (${ok.first},${ok.second})")
                    tap(ok); delay(2500)
                    continue
                }
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

    /**
     * 동굴에서 이름으로 찾아 상세를 연다. 대상 지정·해제가 함께 쓴다.
     * 고르는 일 자체는 selectDragonInCave 하나가 맡는다.
     */
    private suspend fun openDragonDetail(name: String): Boolean =
        selectDragonInCave(name = name, stage = setup.stage)

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
            // 선택을 묻는 창이 앞을 막고 있으면 취소로 물러난다.
            // 확인을 누르면 재화를 쓰거나 원치 않는 것을 확정할 수 있다.
            // 배고픔·레벨업처럼 눌러야 하는 창은 아래에서 따로 다룬다.
            if (!Judge.isHungerPopup(f) && !Judge.isLevelUpScreen(f)) {
                Judge.cancelButton(f)?.let { c ->
                    onLog("    선택창 취소 (${c.first},${c.second})")
                    tap(c); delay(2500)
                    return@repeat
                }
            }
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
            // 전체화면 광고. X 가 없고 아래 검정 띠의 '닫기' 만 있다.
            // X 를 찾다가 광고 그림을 눌러 크롬이 열린 적이 있다. 먼저 본다.
            Judge.adCloseY(f)?.let { ay ->
                // 닫기 줄이 계정삭제 금지구역(x 1030~1300, y 845~935)과 겹치면
                // 한가운데를 누르는 탭이 거부된다. 거부는 화면이 그대로라 조용히
                // 지나가고, 그 다음 탭이 광고 그림에 꽂힌다. 검은 띠는 화면 폭
                // 전체라 옆을 눌러도 같은 줄이다 — 금지구역 밖으로 비켜 누른다.
                val ax = if (ay in 845..935) 1500 else 1170
                onLog("    광고 닫기 ($ax,$ay)"); tap(ax to ay); delay(3500)
                return@repeat
            }
            // 공지 판정(isNotice)은 여기서 쓰지 않는다. 월드맵 하단도 어두워
            // 오탐이 나고, 그러면 같은 자리를 계속 누르게 된다.
            // 공지는 로그인 직후 문맥에서만 확인한다.
            // 공지창은 루프 도중에도 끼어든다. 닫기 띠가 없고 X 자리도 달라
            // 아래 팝업 판정에 안 걸린다. 공지창인 것을 직접 확인하고 닫는다.
            Judge.noticeWindowClose(f)?.let { cx ->
                onLog("    공지창 닫기 (${cx.first},${cx.second})")
                tap(cx); delay(3000)
                return@repeat
            }
            // 여기서는 화면에서 X 를 찾아 아무 창이나 닫으면 안 된다.
            //
            // 동굴은 상단 아이콘 줄을 가리므로 '덮은 창' 으로 보인다. 실제로
            // 동굴에 들어가자마자 그 X (2015,55) 를 눌러 닫아버렸고,
            // 메뉴 → 동굴 → 닫힘 → 메뉴 → 동굴 을 스무 번 반복하다 멈췄다.
            // 동굴·레벨업 창처럼 루프가 스스로 들어간 화면까지 걷어내기 때문이다.
            //
            // 창을 찾아 닫는 것은 로그인 직후 공지 처리에서만 한다. 거기서는
            // 아직 아무 데도 들어가지 않았으므로 닫아서 잃을 것이 없다.
            // 여기서는 예전처럼 정해진 팝업 X 만 본다.
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
                // 노드만 보면 안 된다. 월드맵에서도 입력 노드가 잡혀
                // 로그인 폼으로 읽고 멀쩡한 화면에서 멈췄다.
                // 지도가 보이면 이미 로그인된 것이다 — 그때는 절대 로그인으로 가지 않는다.
                Judge.isIdForm(f) && !Judge.isGameTopBarVisible(f) -> {
                    onLog("    아이디 입력폼 — 자격증명 입력")
                    if (!loginFromForm()) return false
                    quiet = 0
                }
                Judge.adCloseY(f) != null -> {
                    val ay = Judge.adCloseY(f)!!
                    onLog("    광고 닫기 (y=$ay)"); tap(1170 to ay); delay(4000); quiet = 0
                }
                Judge.isNotice(f) -> {
                    onLog("    공지 닫기"); tap(tNoticeClose); delay(4000); quiet = 0
                }
                // 공지는 여러 개가 줄줄이 뜨고 창마다 X 자리가 다르다.
                // 고정 좌표로만 찾다가 새 공지창(1970,57)을 못 닫고
                // 덮인 채로 '게임 준비됨' 으로 넘어갔다. 화면에서 찾는다.
                Judge.redCloseButton(f) != null -> {
                    val cx = Judge.redCloseButton(f)!!
                    onLog("    창 닫기 (${cx.first},${cx.second})")
                    tap(cx); delay(3000); quiet = 0
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

    /**
     * 설정창을 연다.
     *
     * 예전에는 메뉴 버튼과 설정 아이콘을 연달아 눌렀다. 메뉴 버튼은 토글이라
     * 이미 열려 있으면 첫 탭이 닫아버리고, 이어지는 설정 탭이 **지도로 떨어진다.**
     * 설정 아이콘 자리는 월드맵의 아르하 쪽이라 `아르하의 입구` 오버레이가 열리고,
     * 그 뒤로 루프가 동굴과 월드맵을 헤맸다. 실기기에서 그대로 재현했다.
     *
     * 메뉴가 열린 것을 보고 나서 설정을 누른다.
     */
    private suspend fun openSettings(): Boolean {
        repeat(3) { attempt ->
            clearBlockers()
            for (t in 1..3) {
                if (frame()?.let { Judge.isMenuOpen(it) } == true) break
                tap(tMenu); delay(2500)
            }
            if (frame()?.let { Judge.isMenuOpen(it) } != true) {
                onLog("    메뉴가 안 열린다 (${attempt + 1}/3)")
                dumpFrame("settings-menu")
                return@repeat
            }
            // 설정 아이콘 자리는 월드맵의 아르하와 37px 차이다. 메뉴가 열린 것을
            // 확인했으니 메뉴 패널이 그 자리를 덮고 있지만, 눌러보고 설정이 안
            // 열렸으면 지도를 눌렀다는 뜻이다. 아르하에 들어갔을 수 있으니
            // 다음 시도 전에 월드맵으로 되돌린다. 아르하는 루프가 갈 곳이 아니다.
            tap(tSettings); delay(3000)
            val after = frame()
            if (after != null && Judge.isSettingsOpen(after)) return true
            if (after != null && !Judge.isWorldMap(after)) {
                onLog("    설정이 아니라 지도를 눌렀다 — 월드맵으로 되돌린다")
                dumpFrame("settings-miss")
                if (!goToWorldMap()) return false
            }
            onLog("    설정창 미확인 (${attempt + 1}/3) — 재시도")
        }
        return false
    }

    /** 설정창을 열어 저장만 한다 (다이얼로그 복귀 없이). */
    private suspend fun saveOnly(): Boolean {
        if (!openSettings()) { abort("설정창 검증 3회 실패"); return false }
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
                // 표준 X 로 안 닫히는 창이 있다. '데이터를 저장했습니다' 처럼
                // 확인 버튼만 있는 창은 X 를 여덟 번 눌러도 그대로 남는다.
                // 확인이 보이면 그것부터 누른다.
                //
                // 다만 레벨업 다이얼로그일 때는 손대지 않는다. 거기서 확인을 누르면
                // 아이템을 써버린다 — 그건 사이클이 할 일이지 창 정리가 할 일이 아니다.
                val ok = if (Judge.isLevelUpDialog(f)) null else Judge.confirmButton(f)
                if (ok != null) {
                    onLog("    확인 창 닫기 (${ok.first},${ok.second})")
                    tap(ok); delay(2500)
                    continue
                }
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

        if (!openSettings()) { abort("설정창 검증 3회 실패"); return false }

        tap(tSaveSafe); delay(3000)

        // '데이터를 저장했습니다' 확인창은 늦게 뜬다. 좌표를 박아 한 번 누르면
        // 뜨기 전에 헛치고, 그 뒤에 뜬 창이 화면을 덮은 채 남는다.
        // 실제로 그 창이 남아 '큰 창이 덮고 있다' 를 여덟 번 반복하다 멈췄다.
        var closed = false
        repeat(8) {
            val at = frame()?.let { Judge.confirmButton(it) }
            if (at != null) {
                onLog("    저장 확인 (${at.first},${at.second})")
                tap(at); delay(2000)
                closed = true
                return@repeat
            }
            delay(1000)
        }
        if (!closed) onLog("    ! 저장 확인창을 찾지 못했다 — 그대로 진행")

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
        // 칸 위치를 그때그때 화면에서 찾는다.
        // 키보드가 올라오면 폼이 통째로 위로 밀린다 — 실측(v6.25.1):
        // 키보드 없을 때 아이디 460 / 비밀번호 580, 올라오면 222 / 342.
        // 한 번 재서 끝내면 반드시 한쪽이 틀린다.
        fun rowsNow(): Pair<Int, Int>? = frame()?.let { Judge.loginFormRows(it) }

        // 노드로 폼을 감지해도 화면은 아직 로딩 중일 수 있다. 칸이 보일 때까지 기다린다.
        // 바로 중단하면 게임이 뜨는 중에 멀쩡한 로그인이 실패로 끝난다.
        var first: Pair<Int, Int>? = null
        repeat(12) {
            first = rowsNow()
            if (first != null) return@repeat
            delay(1500)
        }
        val rows0 = first ?: run { abort("로그인 폼의 입력칸을 찾지 못했다"); return false }
        onLog("    입력칸(키보드 없음): 아이디 y=${rows0.first} · 비밀번호 y=${rows0.second}")
        tap(1170 to rows0.first); delay(1500)
        if (!typeText(creds.id, expectPassword = false)) { abort("아이디 칸에 포커스가 오지 않았다"); return false }
        delay(800)

        // 키보드가 올라온 뒤 다시 잰다
        val second = rowsNow() ?: run { abort("비밀번호 칸을 찾지 못했다"); return false }
        onLog("    입력칸(키보드 올라옴): 비밀번호 y=${second.second}")
        tap(1170 to second.second); delay(1500)
        // 여기서 칸을 확인하지 않으면 비밀번호가 아이디 칸에 평문으로 들어간다.
        // 실제로 그렇게 되어 화면에 비밀번호가 그대로 보였다.
        if (!typeText(creds.password, expectPassword = true)) { abort("비밀번호 칸에 포커스가 오지 않았다"); return false }
        delay(800)
        a11y?.back(); delay(1500)

        // 키보드를 내린 뒤라야 로그인 버튼이 보인다
        val third = rowsNow() ?: run { abort("로그인 버튼을 찾지 못했다"); return false }
        val loginY = frame()?.let { Judge.loginButtonY(it, third.second) }
            ?: run { abort("로그인 버튼을 찾지 못했다"); return false }
        onLog("    로그인 버튼 y=$loginY")
        tap(1170 to loginY); delay(8000)

        // 로그인 뒤에 무엇이 뜰지는 그때그때 다르다 — 공지일 수도, 전면광고일
        // 수도, 곧장 지도일 수도 있다. 공지 하나만 기다리다 **광고가 떴는데도**
        // '공지창이 안 뜬다' 로 중단했다. 로그인이 됐다는 신호를 넓게 본다.
        val signedIn = waitFor(30000) {
            Judge.isNotice(it) ||
                Judge.adCloseY(it) != null ||
                Judge.noticeWindowClose(it) != null ||
                Judge.isGameTopBarVisible(it)
        }
        if (!signedIn) {
            dumpFrame("after-login")
            abort("로그인 뒤 화면이 바뀌지 않는다"); return false
        }

        // 무엇이 떴든 걷어낸다. 공지는 자리가 정해져 있어 따로 누르고,
        // 나머지는 clearBlockers 가 종류별로 확인해서 처리한다.
        repeat(4) {
            val f = frame() ?: return@repeat
            if (Judge.isNotice(f)) { tap(tNoticeClose); delay(5000); return@repeat }
            clearBlockers()
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
