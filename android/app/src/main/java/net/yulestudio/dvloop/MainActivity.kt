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

private enum class Screen { LOGIN, MENU, SETUP }

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
                            saved = savedLoop(prefs),
                            onOverlaySetting = { openOverlaySetting() },
                            onResume = {
                                prefs.restore(repo)?.let { startLoop(it) }
                            },
                            onNewSetup = { screen = Screen.SETUP },
                            onPick = { kind ->
                                // 지금 열려 있는 것은 레벨업 루프뿐이다
                                if (kind == LoopKind.LEVEL_UP) screen = Screen.SETUP
                            },
                            onLogout = { creds.clear(); screen = Screen.LOGIN },
                        )

                        Screen.SETUP -> SetupFlow(
                            repo = repo,
                            onExit = { screen = Screen.MENU },
                            onStart = { setup ->
                                prefs.save(setup)
                                startLoop(setup)
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
        return SavedLoop(
            dragonName = prefs.dragonName,
            stageLabel = stage.label,
            level = prefs.currentLevel,
            targetLevel = prefs.targetLevel,
            grade = prefs.currentGrade.toDouble(),
            done = prefs.isDone,
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
