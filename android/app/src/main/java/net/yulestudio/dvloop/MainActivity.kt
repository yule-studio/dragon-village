package net.yulestudio.dvloop

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF3182F6)
private val Ink = Color(0xFF191F28)
private val Gray = Color(0xFF8B95A1)
private val Field = Color(0xFFF2F4F6)

const val GAME_PKG = "com.highbrow.game"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = DexRepository(applicationContext)
        val creds = Credentials(applicationContext)

        setContent {
            MaterialTheme {
                var loggedIn by remember { mutableStateOf(creds.isSaved) }
                Surface(Modifier.fillMaxSize(), color = Color.White) {
                    if (!loggedIn) {
                        LoginScreen(initialId = creds.id) { id, pw, remember ->
                            if (remember) {
                                creds.id = id
                                creds.password = pw
                            }
                            loggedIn = true
                        }
                    } else {
                        HomeScreen(repo, creds) { loggedIn = false }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(repo: DexRepository, creds: Credentials, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var total by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(emptyList<Dragon>()) }
    var picked by remember { mutableStateOf<Dragon?>(null) }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap?>() }

    LaunchedEffect(Unit) { total = repo.count() }
    LaunchedEffect(query) { list = repo.search(query, limit = 60) }

    val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(ctx)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("드빌 매크로", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("${creds.id} 로 로그인됨", fontSize = 13.sp, color = Gray)
            }
            TextButton(onClick = { creds.clear(); onLogout() }) {
                Text("로그아웃", color = Gray, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        if (!canOverlay) {
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
                        onClick = {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}"),
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("권한 설정하기") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Text("대상 드래곤", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        picked?.let { d ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Field).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                thumbs[d.no]?.let { Image(it.asImageBitmap(), d.name, Modifier.size(44.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(d.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ink)
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                LoopStatus.clearStop()
                LoopStatus.update(
                    LoopStatus.Snapshot(
                        running = true,
                        target = picked?.name ?: "대상 없음",
                        level = 0, targetLevel = 45,
                    )
                )
                FloatingService.start(ctx)
                ctx.packageManager.getLaunchIntentForPackage(GAME_PKG)?.let {
                    ctx.startActivity(it)
                }
            },
            enabled = canOverlay && picked != null,
            colors = ButtonDefaults.buttonColors(containerColor = Blue),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("드빌 실행하고 시작", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(28.dp))
        Text("도감 ${total}마리", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("이름으로 찾기", color = Gray) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            Modifier.height(360.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(list, key = { it.no }) { d ->
                if (!thumbs.containsKey(d.no)) thumbs[d.no] = repo.thumbnail(d.no)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (picked?.no == d.no) Color(0xFFE8F1FE) else Field)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    thumbs[d.no]?.let { Image(it.asImageBitmap(), d.name, Modifier.size(40.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontSize = 15.sp, color = Ink)
                        Text("#${d.no} · ${d.attr ?: "-"}", fontSize = 12.sp, color = Gray)
                    }
                    TextButton(onClick = { picked = d }) {
                        Text(if (picked?.no == d.no) "선택됨" else "선택",
                            fontSize = 13.sp, color = Blue)
                    }
                }
            }
        }
    }
}
