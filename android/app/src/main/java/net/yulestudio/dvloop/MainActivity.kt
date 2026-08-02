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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private val Blue = Color(0xFF3182F6)
private val Ink = Color(0xFF191F28)
private val Gray = Color(0xFF8B95A1)
private val Field = Color(0xFFF2F4F6)

const val GAME_PKG = "com.highbrow.game"
private const val PAGE = 40

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: DexRepository, creds: Credentials, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var total by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Dragon?>(null) }

    // 무한 스크롤 상태
    val list = remember { mutableStateListOf<Dragon>() }
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }

    val thumbs = remember { mutableStateMapOf<Int, Bitmap?>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { total = withContext(Dispatchers.IO) { repo.count() } }

    // 검색어가 바뀌면 처음부터 다시
    LaunchedEffect(query) {
        loading = true
        reachedEnd = false
        val first = withContext(Dispatchers.IO) { repo.search(query, PAGE, 0) }
        list.clear()
        list.addAll(first)
        reachedEnd = first.size < PAGE
        loading = false
    }

    // 끝이 보이면 다음 쪽을 잇는다
    LaunchedEffect(listState, query) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (!loading && !reachedEnd && list.isNotEmpty() && last >= list.size - 6) {
                    loading = true
                    val next = withContext(Dispatchers.IO) {
                        repo.search(query, PAGE, list.size)
                    }
                    list.addAll(next)
                    reachedEnd = next.size < PAGE
                    loading = false
                }
            }
    }

    val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(ctx)

    // 화면 전체를 하나의 리스트로 둔다.
    // 스크롤 영역 안에 높이 고정 리스트를 넣으면 스크롤이 이중으로 걸려
    // 끝까지 내려가지 않는다.
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()   // 상태바에 제목이 잘리지 않게
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("드빌 매크로", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("${creds.id} 로 로그인됨", fontSize = 13.sp, color = Gray)
                }
                TextButton(onClick = { creds.clear(); onLogout() }) {
                    Text("로그아웃", color = Gray, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (!canOverlay) {
            item {
                OverlayNotice(onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                })
                Spacer(Modifier.height(16.dp))
            }
        }

        item {
            Text("대상 드래곤", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(8.dp))
            val p = picked
            if (p == null) {
                Text(
                    "아래 목록에서 레벨업할 드래곤을 골라 주세요.",
                    fontSize = 13.sp, color = Gray,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE8F1FE)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    thumbs[p.no]?.let {
                        Image(it.asImageBitmap(), p.name, Modifier.size(44.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(p.name, fontSize = 16.sp,
                        fontWeight = FontWeight.Medium, color = Ink)
                }
            }
            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(24.dp))
            Text("도감 ${total}마리", fontSize = 15.sp,
                fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("이름으로 찾기", color = Gray) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }

        items(list, key = { it.no }) { d ->
            if (!thumbs.containsKey(d.no)) thumbs[d.no] = repo.thumbnail(d.no)
            DragonRow(d, thumbs[d.no], picked?.no == d.no) { picked = d }
        }

        item {
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp), color = Blue) }

                list.isEmpty() -> Text(
                    "찾는 이름이 없어요.",
                    fontSize = 13.sp, color = Gray,
                    modifier = Modifier.padding(20.dp),
                )

                reachedEnd -> Text(
                    "${list.size}마리를 모두 불러왔어요.",
                    fontSize = 12.sp, color = Gray,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
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

@Composable
private fun DragonRow(d: Dragon, thumb: Bitmap?, selected: Boolean, onPick: () -> Unit) {
    // 행 전체가 선택 영역이다. 오른쪽 작은 버튼만 누르게 하면 손이 더 간다.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFE8F1FE) else Field)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) {
            Image(thumb.asImageBitmap(), d.name, Modifier.size(34.dp))
        } else {
            Box(Modifier.size(34.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 15.sp, lineHeight = 19.sp, color = Ink)
            Text(
                "#${d.no} · ${d.attr ?: "-"}",
                fontSize = 11.sp, lineHeight = 14.sp, color = Gray,
            )
        }
        if (selected) {
            Text("선택됨", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue)
        }
    }
}
