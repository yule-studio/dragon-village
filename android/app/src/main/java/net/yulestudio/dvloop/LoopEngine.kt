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

    // 로그인
    private val tStart = 1170 to 540
    private val tHighbrow = 1170 to 759
    private val tId = 1170 to 461
    private val tPw = 1170 to 339
    private val tLogin = 1170 to 699

    private var cycle = 0
    private var wins = 0
    private var level = 0

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
        LoopStatus.requestStop()
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
        onLog("루프 시작: ${setup.dragon?.name} → Lv.${setup.targetLevel}")

        if (a11y == null) { abort("접근성 서비스가 꺼져 있다"); return }
        if (!ScreenCapture.isReady) { abort("화면 캡처가 준비되지 않았다"); return }

        if (!openDialog()) return

        while (cycle < maxCycles && !LoopStatus.stopRequested && abortReason == null) {
            cycle++
            publish("levelup")
            val win = doCycle() ?: return

            if (win) {
                wins++
                level++
                onLog("  누적 성공 $wins  (Lv.$level)")
                publish("save")
                if (!save()) return
                if (level >= setup.targetLevel) {
                    onLog("목표 달성. Lv.$level")
                    LoopStatus.update(LoopStatus.current.copy(running = false, phase = "done"))
                    FloatingService.refresh(ctx)
                    return
                }
            } else {
                publish("desak")
                if (!desak()) return
                if (!openDialog()) return
            }
        }
        LoopStatus.update(LoopStatus.current.copy(running = false, phase = "idle"))
        FloatingService.refresh(ctx)
    }

    /** 레벨업 1회. true=트리플맥스, null=중단 */
    private suspend fun doCycle(): Boolean? {
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
        val win = triple && grade == true

        val mark = res.entries.joinToString(" ") { "${it.key}${if (it.value) "O" else "X"}" }
        val gtxt = when (grade) { true -> "7.0"; false -> "7.0미만"; null -> "등급판정불가" }
        onLog("사이클 $cycle: $mark / $gtxt → ${if (win) "★트리플맥스★" else "실패"}")

        // 뱃지와 등급이 어긋나면 내 판정 모델이 틀린 것이다. 저장하지 말고 멈춘다.
        if (triple && grade != true) {
            abort("판정 불일치 — 뱃지는 MAX 인데 등급이 7.0 이 아니다"); return null
        }

        tap(tResultOk); delay(3000)
        return win
    }

    /** 레벨업 다이얼로그까지 들어간다. 용 패널은 토글이라 어긋나면 다시 시도한다. */
    private suspend fun openDialog(): Boolean {
        val f = frame()
        if (f != null && Judge.isTitle(f)) {
            onLog("  타이틀 화면 — 로그인부터")
            if (!login()) return false
        }
        val item = if (setup.stage?.awakened == true) tItemAwaken else tItemNormal
        repeat(3) { attempt ->
            frame()?.let { if (Judge.isTitle(it)) { abort("로그아웃 상태로 되돌아갔다"); return false } }
            tap(tPanel); delay(4000)
            tap(tDragon); delay(3000)
            tap(item); delay(3000)
            if (waitFor(12000) { Judge.isLevelUpDialog(it) }) return true
            onLog("    다이얼로그 미확인 (${attempt + 1}/3) — 재시도")
        }
        abort("레벨업 다이얼로그가 안 열린다 (3회 시도)")
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

        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$GAME_PKG")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        if (!s.waitForText("저장공간", "저장 공간", "Storage", timeoutMs = 8000)) {
            abort("앱 정보 화면을 찾지 못했다"); return false
        }
        if (!s.clickByText("저장공간", "저장 공간", "Storage")) {
            abort("저장공간 항목을 누르지 못했다"); return false
        }
        if (!s.waitForText("데이터 삭제", "저장공간 관리", "Clear data", timeoutMs = 8000)) {
            abort("저장공간 화면을 찾지 못했다"); return false
        }
        if (!s.clickByText("데이터 삭제", "Clear data")) {
            abort("데이터 삭제 버튼을 누르지 못했다"); return false
        }
        // 확인 대화상자
        delay(1000)
        s.clickByText("삭제", "확인", "OK", "Delete")
        delay(2500)

        // 게임 재실행
        ctx.packageManager.getLaunchIntentForPackage(GAME_PKG)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        } ?: run { abort("게임을 실행하지 못했다"); return false }

        delay(22000)
        return login()
    }

    /** 반드시 하이브로 계정으로만 로그인한다. 확인 안 되면 중단. */
    private suspend fun login(): Boolean {
        onLog("  로그인 시작")
        tap(tStart); delay(5000)

        // 구글로 새는 사고 방지 — 3버튼 선택 화면을 확인해야만 진행
        if (!waitFor(20000) { Judge.isLoginSelect(it) }) {
            abort("로그인 선택 화면이 아니다 (구글 자동로그인 의심)"); return false
        }
        tap(tHighbrow); delay(3000)

        // 하이브로 입력폼이 떠야만 자격증명을 넣는다
        if (!waitFor(15000) { Judge.isIdForm(it) }) {
            abort("하이브로 아이디 입력폼이 안 뜬다 — 잘못된 로그인 경로"); return false
        }

        tap(tId); delay(1500)
        typeText(creds.id); delay(800)
        tap(tPw); delay(1500)
        typeText(creds.password); delay(800)
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

    /** 접근성으로 입력칸에 글자를 넣는다. adb 와 달리 한글도 들어간다. */
    private fun typeText(text: String) {
        val s = a11y ?: return
        val root = s.rootInActiveWindow ?: return
        try {
            val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused == null) {
                Log.w("DvLoop", "입력 포커스를 찾지 못했다")
                return
            }
            val args = android.os.Bundle().apply {
                putCharSequence(
                    android.view.accessibility.AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }
            focused.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
            )
        } finally {
            runCatching { root.recycle() }
        }
    }
}
