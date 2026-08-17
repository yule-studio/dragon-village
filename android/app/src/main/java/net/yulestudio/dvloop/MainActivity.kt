package net.yulestudio.dvloop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

const val GAME_PKG = "com.highbrow.game"

private enum class Screen { LOGIN, MENU, SETUP, MULTI_COUNT, MULTI }

/** 다중 레벨업에 한 번에 담을 수 있는 마릿수. */
private const val MULTI_MAX = 5

class MainActivity : ComponentActivity() {

    private var pendingSetup: LoopSetup? = null

    // 미니창의 '재시작' 을 받아 처리하려면 화면 밖에서도 설정을 읽어야 한다.
    private val repo by lazy { DexRepository(applicationContext) }
    private val prefs by lazy { LoopPrefs(applicationContext) }

    /** 화면 캡처 허용을 받아 루프를 시작한다. 매번 사용자가 눌러야 한다. */
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val setup = pendingSetup
        if (result.resultCode == RESULT_OK && data != null && setup != null) {
            LoopService.start(this, setup, result.resultCode, data)
            packageManager.getLaunchIntentForPackage(GAME_PKG)?.let { startActivity(it) }
        } else {
            LoopStatus.update(LoopStatus.current.copy(running = false))
            LoopService.log("화면 캡처 허용을 받지 못해 시작하지 않았다")
        }
        pendingSetup = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val creds = Credentials(applicationContext)

        setContent {
            MaterialTheme {
                var screen by remember {
                    mutableStateOf(if (creds.isSaved) Screen.MENU else Screen.LOGIN)
                }
                // 설정 화면은 루프 종류마다 묻는 것이 다르다
                var kind by remember { mutableStateOf(LoopKind.LEVEL_UP) }
                // 설정을 마쳤을 때 지금 대상으로 삼을지, 대기열에 넣을지
                var appendToQueue by remember { mutableStateOf(false) }
                var queueVersion by remember { mutableStateOf(0) }
                // 다중 레벨업으로 담는 중인 대상들. 비어 있으면 다중 모드가 아니다.
                var multi by remember { mutableStateOf(false) }
                var multiTarget by remember { mutableStateOf(0) }
                var picked by remember { mutableStateOf(listOf<LoopSetup>()) }
                // 권한은 설정 화면에 다녀오면 바뀔 수 있다. 화면이 돌아올 때마다 다시 본다.
                var canOverlay by remember { mutableStateOf(hasOverlay()) }
                LaunchedEffect(screen) { canOverlay = hasOverlay() }

                Surface(Modifier.fillMaxSize(), color = Color.White) {
                    when (screen) {
                        Screen.LOGIN -> LoginScreen(initialId = creds.id) { id, pw, remember ->
                            if (remember) {
                                creds.id = id
                                creds.password = pw
                            }
                            screen = Screen.MENU
                        }

                        Screen.MENU -> LoopMenu(
                            creds = creds,
                            canOverlay = canOverlay,
                            saved = remember(screen, queueVersion) { savedLoop(prefs) },
                            onOverlaySetting = { openOverlaySetting() },
                            onResume = {
                                prefs.restore(repo)?.let { startLoop(it) }
                            },
                            onNewSetup = { appendToQueue = false; screen = Screen.SETUP },
                            onAddNext = {
                                appendToQueue = true
                                kind = LoopKind.LEVEL_UP
                                screen = Screen.SETUP
                            },
                            onPick = { chosen ->
                                when {
                                    // 미션은 대상 드래곤이 필요 없다. 바로 돈다.
                                    chosen == LoopKind.MISSION ->
                                        startLoop(LoopSetup(kind = LoopKind.MISSION))
                                    chosen == LoopKind.MULTI_LEVEL_UP -> {
                                        multi = true
                                        multiTarget = 0
                                        picked = emptyList()
                                        kind = LoopKind.LEVEL_UP
                                        screen = Screen.MULTI_COUNT
                                    }
                                    chosen.ready -> {
                                        multi = false
                                        kind = chosen
                                        screen = Screen.SETUP
                                    }
                                }
                            },
                            onLogout = { creds.clear(); screen = Screen.LOGIN },
                        )

                        Screen.SETUP -> SetupFlow(
                            repo = repo,
                            kind = kind,
                            onExit = { screen = Screen.MENU },
                            onStart = { setup ->
                                when {
                                    // 다중은 다 담을 때까지 시작하지 않는다.
                                    multi -> {
                                        picked = picked + setup
                                        screen = Screen.MULTI
                                    }
                                    appendToQueue -> {
                                        // 지금 도는 것을 건드리지 않고 줄만 세운다
                                        prefs.enqueue(setup)
                                        appendToQueue = false
                                        queueVersion++
                                        screen = Screen.MENU
                                    }
                                    else -> {
                                        prefs.save(setup)
                                        startLoop(setup)
                                        screen = Screen.MENU
                                    }
                                }
                            },
                        )

                        Screen.MULTI_COUNT -> MultiCount(
                            max = MULTI_MAX,
                            onPick = { n -> multiTarget = n; screen = Screen.SETUP },
                            onBack = { multi = false; screen = Screen.MENU },
                        )

                        Screen.MULTI -> MultiReview(
                            picked = picked,
                            max = multiTarget.coerceIn(1, MULTI_MAX),
                            onAdd = { screen = Screen.SETUP },
                            onBack = { multi = false; screen = Screen.MENU },
                            onStart = {
                                val list = picked
                                if (list.isNotEmpty()) {
                                    // 앞엣것을 지금 대상으로 올리고 나머지는 줄을 세운다.
                                    // 이전 대기열은 지운다 — 남겨두면 이번에 짠 순서
                                    // 뒤에 예전 것이 붙어 엉뚱한 용이 이어진다.
                                    prefs.clearQueue()
                                    prefs.save(list.first())
                                    list.drop(1).forEach { prefs.enqueue(it) }
                                    queueVersion++
                                    multi = false
                                    picked = emptyList()
                                    startLoop(list.first())
                                }
                                screen = Screen.MENU
                            },
                        )
                    }
                }
            }
        }
    }

    private fun savedLoop(prefs: LoopPrefs): SavedLoop? {
        if (!prefs.hasSetup) return null
        val stage = runCatching { Stage.valueOf(prefs.stageName) }.getOrNull() ?: return null
        val kind = runCatching { LoopKind.valueOf(prefs.kindName) }
            .getOrDefault(LoopKind.LEVEL_UP)
        return SavedLoop(
            dragonName = prefs.dragonName,
            stageLabel = stage.label,
            level = prefs.currentLevel,
            targetLevel = prefs.targetLevel,
            grade = prefs.currentGrade.toDouble(),
            done = prefs.isDone,
            kindTitle = kind.title,
            queue = prefs.queue.mapNotNull { q ->
                repo.byNo(q.no)?.let { d ->
                    val st = runCatching { Stage.valueOf(q.stage) }.getOrNull()
                    "${d.name} ${st?.label ?: ""} → Lv.${q.target}"
                }
            },
            minGrade = if (kind == LoopKind.EXPLORE) prefs.minGrade.toDouble() else null,
        )
    }

    private fun hasOverlay() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun openOverlaySetting() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    /** 앱이 앞에 있으면 미니창은 필요 없다. 앱 화면이 곧 메인이다. */
    override fun onResume() {
        super.onResume()
        FloatingService.stop(this)
        // 미니창에서 '재시작' 을 눌러 넘어온 경우. 화면 캡처 허용은 여기서만 받을 수 있다.
        if (LoopStatus.consumeResume() && !LoopStatus.current.running) {
            prefs.restore(repo)?.let { startLoop(it) }
                ?: LoopService.log("이어서 시작할 설정이 없다")
        }
    }

    /** 앱을 벗어났는데 루프가 돌고 있으면 미니창으로 이어 붙인다. */
    override fun onPause() {
        super.onPause()
        if (LoopStatus.current.running) FloatingService.start(this)
    }

    private fun startLoop(setup: LoopSetup) {
        if (!LoopAccessibilityService.isRunning) {
            LoopService.log("접근성 서비스가 꺼져 있다. 설정에서 켜야 한다.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        LoopStatus.clearStop()
        LoopStatus.update(
            LoopStatus.Snapshot(
                running = true,
                target = setup.dragon?.name ?: "대상 없음",
                stage = setup.stage?.label ?: "-",
                level = setup.currentLevel,
                targetLevel = setup.targetLevel,
            )
        )
        pendingSetup = setup
        // 화면 캡처 허용 → 허용되면 서비스가 뜨고 게임이 실행된다
        captureLauncher.launch(ScreenCapture.permissionIntent(this))
    }
}
