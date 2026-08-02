package net.yulestudio.dvloop

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val GAME_PKG = "com.highbrow.game"

private enum class Screen { LOGIN, HOME, SETUP }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = DexRepository(applicationContext)
        val creds = Credentials(applicationContext)

        setContent {
            MaterialTheme {
                var screen by remember {
                    mutableStateOf(if (creds.isSaved) Screen.HOME else Screen.LOGIN)
                }
                Surface(Modifier.fillMaxSize(), color = Color.White) {
                    when (screen) {
                        Screen.LOGIN -> LoginScreen(initialId = creds.id) { id, pw, remember ->
                            if (remember) {
                                creds.id = id
                                creds.password = pw
                            }
                            screen = Screen.HOME
                        }

                        Screen.HOME -> HomeScreen(
                            creds = creds,
                            onSetup = { screen = Screen.SETUP },
                            onLogout = { creds.clear(); screen = Screen.LOGIN },
                        )

                        Screen.SETUP -> SetupFlow(
                            repo = repo,
                            onExit = { screen = Screen.HOME },
                            onStart = { setup -> startLoop(setup); screen = Screen.HOME },
                        )
                    }
                }
            }
        }
    }

    /** 앱이 앞에 있으면 미니창은 필요 없다. 앱 화면이 곧 메인이다. */
    override fun onResume() {
        super.onResume()
        FloatingService.stop(this)
    }

    /** 앱을 벗어났는데 루프가 돌고 있으면 미니창으로 이어 붙인다. */
    override fun onPause() {
        super.onPause()
        if (LoopStatus.current.running) FloatingService.start(this)
    }

    private fun startLoop(setup: LoopSetup) {
        LoopStatus.clearStop()
        LoopStatus.update(
            LoopStatus.Snapshot(
                running = true,
                target = setup.dragon?.name ?: "대상 없음",
                stage = setup.stage?.label ?: "-",
                level = 0,
                targetLevel = setup.targetLevel,
            )
        )
        // 게임을 띄우면 onPause 가 불려 미니창이 따라 붙는다
        packageManager.getLaunchIntentForPackage(GAME_PKG)?.let { startActivity(it) }
    }
}

@Composable
fun HomeScreen(creds: Credentials, onSetup: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(ctx)

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        // 로그아웃은 제목 첫 줄에 맞춘다. 가운데 정렬하면 두 줄짜리 왼쪽과 어긋난다.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("드빌 매크로", fontSize = 22.sp, lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text("${creds.id} 로 로그인됨", fontSize = 13.sp, color = Gray)
            }
            TextButton(
                onClick = onLogout,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) { Text("로그아웃", color = Gray, fontSize = 13.sp) }
        }

        Spacer(Modifier.height(28.dp))

        if (!canOverlay) {
            OverlayNotice {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}"),
                    )
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        Column(
            Modifier.fillMaxWidth().background(Field, RoundedCornerShape(16.dp)).padding(18.dp)
        ) {
            Text("레벨업 루프", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "드래곤과 단계, 목표 레벨을 차례로 고르면\n드빌을 실행하고 바로 시작해요.",
                fontSize = 13.sp, lineHeight = 20.sp, color = Gray,
            )
        }

        Spacer(Modifier.weight(1f))
        PrimaryButton("루프 설정하기", enabled = canOverlay, onClick = onSetup)
        if (!canOverlay) {
            Spacer(Modifier.height(8.dp))
            Text(
                "미니창 권한을 먼저 켜 주세요.",
                fontSize = 12.sp, color = Gray,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OverlayNotice(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E6)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("미니창 권한이 필요해요", fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = Ink)
            Text(
                "게임 화면 위에 작은 창을 띄워 진행 상황을 보고 멈출 수 있어요.",
                fontSize = 13.sp, color = Gray, modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(10.dp),
            ) { Text("권한 설정하기") }
        }
    }
}
