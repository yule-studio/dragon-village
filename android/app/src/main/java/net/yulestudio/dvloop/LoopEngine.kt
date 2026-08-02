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
    private val tGridOpen = 532 to 523
    private val tGridClose = 2003 to 523
    private val tSearch = 1515 to 103
    private val tFirstCell = 359 to 246
    private val tStar = 1732 to 936
    private val tCaveX = 2012 to 53

    // 로그인
    private val tStart = 1170 to 540
    private val tHighbrow = 1170 to 759
    private val tId = 1170 to 461
    private val tPw = 1170 to 339
    private val tLogin = 1170 to 699

    private var cycle = 0
    private var wins = 0
    private var level = setup.currentLevel

    /** 7.0 은 육각형이 시안이라 색으로 교차검증이 된다. 그 미만은 모두 금색이라 불가. */
    private val canVerifyGradeByColor = setup.currentGrade >= 6.95

    var abortReason: String? = null
        private set

    private suspend fun tap(p: Pair<Int, Int>) {
        a11y?.tap(p.first, p.second)
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
            if (!waitForGameReady()) return
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
        when (Judge.isFavorited(frame() ?: return)) {
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

    /** 동굴에서 이름으로 찾아 상세를 연다. 지정·해제가 함께 쓴다. */
    private suspend fun openDragonDetail(name: String): Boolean {
        if (!waitForGameReady()) return false

        // 동굴 진입 → 그리드 펼치기 → 검색.
        // 전환 중에는 탭이 먹지 않아 한 번에 못 들어가는 일이 잦다.
        // 실제로 동굴이 안 열린 채 이후 탭이 월드맵을 건드려 지도가 넘어갔다.
        // 검색창에 글자가 들어갔는지를 성공 기준으로 삼아 재시도한다.
        // (접근성으로 입력하므로 한글이 들어간다 — adb 로는 불가능했다)
        var typed = false
        for (attempt in 1..3) {
            clearBlockers()
            tap(tCave); delay(5000)
            clearBlockers()
            tap(tGridOpen); delay(4000)
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
            // 동굴에 남아 있으면 월드맵으로 되돌린다.
            // 루프는 월드맵에서 시작한다고 가정하므로, 동굴에 있으면
            // 용 패널 탭이 먹지 않아 다이얼로그가 안 열린다.
            frame()?.let {
                if (Judge.isCaveScreen(it)) {
                    onLog("    동굴에 있음 — 월드맵으로 되돌림")
                    tap(tCaveX); delay(4000)
                    return@repeat
                }
            }
            // 게임 밖 창이 올라왔는가 (재난문자 · 전화 · 다른 앱 알림)
            val pkg = a11y?.foregroundPackage()
            if (pkg != null && pkg != GAME_PKG && pkg != ctx.packageName) {
                onLog("    방해 창 감지: $pkg — 닫기 시도")
                a11y?.dismissSystemDialog()
                delay(2500)
                return@repeat
            }
            val f = frame() ?: return
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
        repeat(4) { attempt ->
            // 알림 배너를 잘못 눌러 다른 앱이 떴을 수 있다. 매 시도마다 확인한다.
            clearBlockers()
            frame()?.let { if (Judge.isTitle(it)) { abort("로그아웃 상태로 되돌아갔다"); return false } }
            if (attempt % 2 == 0) { tap(tPanel); delay(4000) }
            tap(tDragon); delay(3000)
            tap(item); delay(3000)
            if (waitFor(12000) { Judge.isLevelUpDialog(it) }) return true
            onLog("    다이얼로그 미확인 (${attempt + 1}/4) — 재시도")
        }
        abort("레벨업 다이얼로그가 안 열린다 (4회 시도)")
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
