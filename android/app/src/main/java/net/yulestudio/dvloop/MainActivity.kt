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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
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
    var attr by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 무한 스크롤 상태
    val list = remember { mutableStateListOf<Dragon>() }
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }

    val thumbs = remember { mutableStateMapOf<Int, Bitmap?>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { total = withContext(Dispatchers.IO) { repo.count() } }

    // 검색어가 바뀌면 처음부터 다시
    LaunchedEffect(query, attr, type) {
        loading = true
        reachedEnd = false
        val first = withContext(Dispatchers.IO) { repo.search(query, PAGE, 0, attr, type) }
        list.clear()
        list.addAll(first)
        reachedEnd = first.size < PAGE
        loading = false
    }

    // 끝이 보이면 다음 쪽을 잇는다
    LaunchedEffect(listState, query, attr, type) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                if (!loading && !reachedEnd && list.isNotEmpty() && last >= list.size - 6) {
                    loading = true
                    val next = withContext(Dispatchers.IO) {
                        repo.search(query, PAGE, list.size, attr, type)
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
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
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
                    onClick = { creds.clear(); onLogout() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
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
            Spacer(Modifier.height(10.dp))

            FilterRow("속성", ATTRS, attr) { attr = it }
            Spacer(Modifier.height(6.dp))
            FilterRow("타입", TYPES, type) { type = it }
            Spacer(Modifier.height(10.dp))
        }

        items(list, key = { it.no }) { d ->
            if (!thumbs.containsKey(d.no)) thumbs[d.no] = repo.thumbnail(d.no)
            DragonRow(d, thumbs[d.no], picked?.no == d.no,
                repo.attrLabel(d.attr), repo.typeLabel(d.type)) { picked = d }
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

    // 1215마리를 손가락으로만 오르내리기는 무리다.
    JumpButtons(
        show = list.size > 12,
        onTop = { scope.launch { listState.scrollToItem(0) } },
        onBottom = {
            scope.launch {
                // 남은 쪽을 모두 불러온 뒤 끝으로 간다.
                // 안 그러면 '맨 아래' 가 로딩된 데까지만 가서 끝처럼 보이지 않는다.
                while (!reachedEnd) {
                    loading = true
                    val next = withContext(Dispatchers.IO) {
                        repo.search(query, 200, list.size, attr, type)
                    }
                    list.addAll(next)
                    reachedEnd = next.size < 200
                    loading = false
                }
                listState.scrollToItem(list.size + 1)
            }
        },
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp),
    )
    }
}

@Composable
private fun JumpButtons(
    show: Boolean,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!show) return
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        JumpButton("↑", onTop)
        Spacer(Modifier.height(8.dp))
        JumpButton("↓", onBottom)
    }
}

@Composable
private fun JumpButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE6191F28))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** 도감 필터 한 줄. 가로로 넘겨 고른다. */
@Composable
private fun FilterRow(
    label: String,
    options: List<Pair<String?, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Gray, modifier = Modifier.width(32.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (code, name) ->
                val on = selected == code
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Blue else Field)
                        .clickable { onSelect(if (on && code != null) null else code) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        name,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Color.White else Ink,
                    )
                }
            }
        }
    }
}

private val ATTRS = listOf<Pair<String?, String>>(
    null to "전체", "F" to "불", "W" to "바람", "A" to "물", "E" to "땅",
    "L" to "빛", "D" to "어둠", "N" to "무", "DA" to "DA", "DU" to "DU",
)

private val TYPES = listOf<Pair<String?, String>>(
    null to "전체", "NONE" to "중립", "H" to "체력", "A" to "공격", "D" to "방어",
    "HA" to "체공", "AD" to "공방", "HD" to "체방",
)

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
private fun DragonRow(
    d: Dragon, thumb: Bitmap?, selected: Boolean,
    attrName: String, typeName: String, onPick: () -> Unit,
) {
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
                "#${d.no} · $attrName · $typeName",
                fontSize = 11.sp, lineHeight = 14.sp, color = Gray,
            )
        }
        if (selected) {
            Text("선택됨", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue)
        }
    }
}
