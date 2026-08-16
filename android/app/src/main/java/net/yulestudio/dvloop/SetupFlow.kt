package net.yulestudio.dvloop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 루프를 걸기까지의 설정 흐름.
 *
 *   1 드래곤   검색·필터로 고른다
 *   2 단계     캡슐 / 성체 / 각성 / 초월 / 강림 — 쓸 아이템이 갈린다
 *   3 목표     몇 레벨까지 올릴지
 *   4 확인     고른 것을 다시 보여주고 시작
 *
 * 한 화면에 모두 두면 무엇을 정해야 하는지 눈에 들어오지 않는다.
 * 한 걸음에 하나씩 묻는다.
 */

val Blue = Color(0xFF3182F6)
val Ink = Color(0xFF191F28)
val Gray = Color(0xFF8B95A1)
val Line = Color(0xFFE5E8EB)
val Field = Color(0xFFF2F4F6)

private const val PAGE = 40

/**
 * 진화 단계. 번호는 도감 사이트 정의를 그대로 쓴다.
 * 0 알 · 1 해치 · 2 해츨링 · 3 성체 · 4 캡슐 · 5 각성 · 6 초월 · 7 강림
 *
 * 루프를 걸 수 있는 것은 성체 이후다. 순서도 게임과 같게 둔다.
 */
enum class Stage(val step: Int, val label: String, val hint: String, val awakened: Boolean) {
    ADULT(3, "성체", "일반 레벨업 아이템을 씁니다", false),
    CAPSULE(4, "캡슐", "각성 전 단계", false),
    AWAKENED(5, "각성", "각성 전용 아이템만 먹습니다", true),
    TRANSCEND(6, "초월", "각성 이후 단계", true),
    DESCEND(7, "강림", "각성 이후 단계", true),
}

data class LoopSetup(
    val dragon: Dragon? = null,
    val stage: Stage? = null,
    val currentLevel: Int = 1,
    /**
     * 지금 등급. 이 값을 유지하는 것이 목표다.
     *
     * 등급은 누적 평균이라 트리플 맥스면 그대로 유지되고, 하나라도 놓치면 떨어진다.
     * 그래서 판정 자체는 뱃지 3개로 충분하다.
     * 다만 7.0 은 육각형이 시안이라 색으로 교차검증까지 되고,
     * 7.0 미만은 모두 금색이라 색만으로는 구분되지 않는다.
     */
    val currentGrade: Double = 7.0,
    val targetLevel: Int = 45,
    /** 어떤 루프인지. 레벨업(데삭)과 탐험은 성공 기준이 다르다. */
    val kind: LoopKind = LoopKind.LEVEL_UP,
    /**
     * 등급 하한. 탐험 루프에서만 쓴다.
     *
     * 레벨업 루프는 정해진 등급을 '그대로' 지키는 것이 목표라 한 번만 놓쳐도 데삭이다.
     * 탐험 루프는 그렇게까지 조이지 않는다. 이 값 아래로만 안 내려가면 된다.
     */
    val minGrade: Double = 3.0,
    /**
     * 한 레벨에서 마이너스 레벨업을 몇 개까지 쓰고 데삭할지.
     *
     * 등급이 하한에 걸리면 마이너스로 되돌려 다시 올리면 된다.
     * 다만 그게 계속 실패하면 마이너스만 축내므로, 이 개수를 넘으면 데삭이 싸다.
     */
    val minusBudget: Int = 5,
)

@Composable
fun SetupFlow(
    repo: DexRepository,
    kind: LoopKind = LoopKind.LEVEL_UP,
    onStart: (LoopSetup) -> Unit,
    onExit: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    var setup by remember { mutableStateOf(LoopSetup(kind = kind)) }

    // 탐험 루프와 레벨업 루프는 등급 하한을 따로 묻는다. 그만큼 단계가 하나 는다.
    //
    // 레벨업도 하한이 필요하다. 예전에는 지금 등급을 그대로 지키는 것만 했는데,
    // 6.9 인 용을 6.5 까지 허용하며 올리고 싶은 경우가 있다. 하한이 없으면
    // 조금만 떨어져도 실패로 보고 되돌리느라 진도가 안 나간다.
    val asksFloor = kind == LoopKind.EXPLORE || kind == LoopKind.LEVEL_UP
    val titles = remember(kind) {
        if (asksFloor)
            listOf("드래곤", "단계", "현재 상태", "등급 하한", "목표 레벨", "확인")
        else
            listOf("드래곤", "단계", "현재 상태", "목표 레벨", "확인")
    }
    val last = titles.lastIndex

    BackHandler { if (step > 0) step-- else onExit() }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        StepBar(step, titles, onBack = { if (step > 0) step-- else onExit() })

        // 하한을 묻는 루프는 3번 자리에 '등급 하한' 이 끼어든다.
        // 그 뒤 단계는 한 칸씩 밀린다.
        val floorStep = if (asksFloor) 3 else -1
        val levelStep = if (asksFloor) 4 else 3

        when (step) {
            0 -> DragonStep(repo, setup.dragon) {
                setup = setup.copy(dragon = it); step = 1
            }
            1 -> StageStep(repo, setup.dragon, setup.stage) {
                setup = setup.copy(stage = it); step = 2
            }
            2 -> CurrentStep(setup.currentLevel, setup.currentGrade) { lv, gr ->
                setup = setup.copy(
                    currentLevel = lv,
                    currentGrade = gr,
                    // 하한이 지금 등급보다 높으면 시작하자마자 실패로 읽힌다
                    minGrade = minOf(setup.minGrade, gr),
                )
                step = 3
            }
            floorStep -> FloorStep(setup.currentGrade, setup.minGrade, setup.minusBudget) { fl, bd ->
                setup = setup.copy(minGrade = fl, minusBudget = bd); step = 4
            }
            levelStep -> LevelStep(
                setup.targetLevel.coerceAtLeast(setup.currentLevel + 1),
                kind,
            ) {
                setup = setup.copy(targetLevel = it); step = last
            }
            else -> ConfirmStep(repo, setup) { onStart(setup) }
        }
    }
}

@Composable
private fun StepBar(step: Int, titles: List<String>, onBack: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "←",
                fontSize = 20.sp,
                color = Ink,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp),
            )
            Text("${step + 1}/${titles.size}  ${titles[step]}", fontSize = 14.sp, color = Gray)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(titles.size) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) Blue else Line)
                )
            }
        }
    }
}

// ---------------- 1단계: 드래곤 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DragonStep(repo: DexRepository, current: Dragon?, onPick: (Dragon) -> Unit) {
    var query by remember { mutableStateOf("") }
    var attr by remember { mutableStateOf<String?>(null) }
    var type by remember { mutableStateOf<String?>(null) }

    val list = remember { mutableStateListOf<Dragon>() }
    var loading by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    val thumbs = remember { mutableStateMapOf<Int, Bitmap?>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, attr, type) {
        loading = true
        reachedEnd = false
        val first = withContext(Dispatchers.IO) { repo.search(query, PAGE, 0, attr, type) }
        list.clear(); list.addAll(first)
        reachedEnd = first.size < PAGE
        loading = false
        listState.scrollToItem(0)
    }

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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("어떤 드래곤을\n올릴까요?", fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(16.dp))

        // 검색과 필터는 고정. 아래 목록만 흐른다.
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
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(list, key = { it.no }) { d ->
                    if (!thumbs.containsKey(d.no)) thumbs[d.no] = repo.thumbnail(d.no)
                    DragonRow(
                        d, thumbs[d.no], current?.no == d.no,
                        repo.attrLabel(d.attr), repo.typeLabel(d.type),
                    ) { onPick(d) }
                }
                item {
                    when {
                        loading -> Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(Modifier.size(22.dp), color = Blue) }

                        list.isEmpty() -> Text("찾는 이름이 없어요.",
                            fontSize = 13.sp, color = Gray,
                            modifier = Modifier.padding(16.dp))

                        reachedEnd -> Text("${list.size}마리를 모두 불러왔어요.",
                            fontSize = 12.sp, color = Gray,
                            modifier = Modifier.padding(vertical = 16.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (list.size > 12) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    JumpButton("↑") { scope.launch { listState.scrollToItem(0) } }
                    Spacer(Modifier.height(8.dp))
                    JumpButton("↓") {
                        scope.launch {
                            // 남은 쪽을 모두 불러온 뒤 끝으로 간다.
                            // 안 그러면 로딩된 데까지만 가서 끝인 줄 착각하게 된다.
                            while (!reachedEnd) {
                                val next = withContext(Dispatchers.IO) {
                                    repo.search(query, 200, list.size, attr, type)
                                }
                                list.addAll(next)
                                reachedEnd = next.size < 200
                            }
                            listState.scrollToItem(list.size)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 2단계: 단계 ----------------

@Composable
private fun StageStep(
    repo: DexRepository,
    dragon: Dragon?,
    current: Stage?,
    onPick: (Stage) -> Unit,
) {
    // 이 드래곤이 실제로 가진 단계만 고르게 한다.
    // 1215마리 중 930마리는 성체까지뿐이라, 다 보여주면 대부분 잘못된 선택지가 된다.
    var steps by remember(dragon) { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(dragon) {
        steps = dragon?.let { withContext(Dispatchers.IO) { repo.availableSteps(it.no) } }
            ?: emptySet()
    }
    val usable = Stage.entries.filter { it.step in steps }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text("어느 단계인가요?", fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (usable.size <= 1)
                "${dragon?.name ?: "이 드래곤"} 은 ${usable.firstOrNull()?.label ?: "-"} 단계만 있어요."
            else
                "단계에 따라 쓸 수 있는 레벨업 아이템이 달라요.\n게임에서 그 드래곤의 단계를 확인하고 골라 주세요.",
            fontSize = 14.sp, lineHeight = 21.sp, color = Gray,
        )
        Spacer(Modifier.height(24.dp))

        usable.forEach { s ->
            val on = current == s
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) Color(0xFFE8F1FE) else Field)
                    .then(
                        if (on) Modifier.border(1.5.dp, Blue, RoundedCornerShape(14.dp))
                        else Modifier
                    )
                    .clickable { onPick(s) }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                    if (s.awakened) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "각성 계열",
                            fontSize = 11.sp, color = Blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFDCE9FD))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(s.hint, fontSize = 13.sp, color = Gray)
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- 3단계: 현재 상태 ----------------

@Composable
private fun CurrentStep(level: Int, grade: Double, onNext: (Int, Double) -> Unit) {
    var lv by remember { mutableStateOf(level) }
    var gr by remember { mutableStateOf(grade) }

    // 내용은 위, 버튼은 아래. 엄지가 닿는 자리에 행동을 둔다.
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
      Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
      ) {
        Text("지금 상태를\n알려주세요", fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "게임에서 그 드래곤의 레벨과 등급을 보고 그대로 넣어 주세요.\n" +
                "이 등급을 지키면서 올립니다.",
            fontSize = 14.sp, lineHeight = 21.sp, color = Gray,
        )

        Spacer(Modifier.height(32.dp))

        Text("현재 레벨", fontSize = 13.sp, color = Gray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton("−") { if (lv > 1) lv-- }
            Text("Lv.$lv", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Blue,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepperButton("+") { if (lv < 44) lv++ }
        }

        Spacer(Modifier.height(28.dp))

        Text("현재 등급", fontSize = 13.sp, color = Gray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton("−") { if (gr > 1.0) gr = Math.round((gr - 0.1) * 10) / 10.0 }
            Text(String.format("%.1f", gr), fontSize = 30.sp,
                fontWeight = FontWeight.Bold, color = Blue,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepperButton("+") { if (gr < 7.0) gr = Math.round((gr + 0.1) * 10) / 10.0 }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (gr >= 6.95)
                "7.0 은 육각형 색으로 한 번 더 확인할 수 있어요."
            else
                "7.0 미만은 색이 모두 금색이라, 능력치 MAX 세 개로만 판단해요.",
            fontSize = 12.sp, lineHeight = 18.sp, color = Gray,
        )

        Spacer(Modifier.height(24.dp))
      }
      PrimaryButton("다음") { onNext(lv, gr) }
      Spacer(Modifier.height(24.dp))
    }
}

// ---------------- 탐험 전용: 등급 하한 ----------------

/**
 * 탐험 루프는 등급을 '유지' 하는 게 아니라 '떨어지지 않게' 하는 것이다.
 * 하한과, 되돌리는 데 쓸 마이너스 개수를 함께 정한다.
 */
@Composable
private fun FloorStep(
    currentGrade: Double,
    floor: Double,
    budget: Int,
    onNext: (Double, Int) -> Unit,
) {
    var fl by remember { mutableStateOf(floor.coerceAtMost(currentGrade)) }
    var bd by remember { mutableStateOf(budget) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("등급은 어디까지\n허용할까요?", fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "올리다 보면 등급이 조금씩 흔들려요.\n" +
                "이 값 아래로만 안 내려가면 그대로 계속 올립니다.\n" +
                "내려가면 마이너스로 되돌린 뒤 다시 올려요.",
            fontSize = 14.sp, lineHeight = 21.sp, color = Gray,
        )

        Spacer(Modifier.height(32.dp))

        Text("등급 하한", fontSize = 13.sp, color = Gray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 0.1 을 더하고 빼는 데 자르기(toInt)를 쓰면 제자리에 멈춘다.
            // 4.1 + 0.1 은 4.1999... 이라 10 배해서 자르면 41 → 다시 4.1 이다.
            // 실제로 하한을 6.5 로 올리려다 4.1 에서 더 안 올라갔다. 반올림한다.
            StepperButton("−") { if (fl > 1.0) fl = Math.round((fl - 0.1) * 10) / 10.0 }
            Text(String.format("%.1f", fl), fontSize = 30.sp,
                fontWeight = FontWeight.Bold, color = Blue,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepperButton("+") {
                if (fl < currentGrade) fl = Math.round((fl + 0.1) * 10) / 10.0
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "지금 등급 ${String.format("%.1f", currentGrade)} 보다 높게는 둘 수 없어요.",
            fontSize = 12.sp, color = Gray,
        )

        Spacer(Modifier.height(28.dp))

        Text("데삭 기준", fontSize = 13.sp, color = Gray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton("−") { if (bd > 1) bd-- }
            Text("마이너스 $bd 개", fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = Blue,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepperButton("+") { if (bd < 20) bd++ }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "한 레벨에서 마이너스를 이만큼 쓰고도 하한을 못 지키면 데삭합니다.\n" +
                "그 전까지는 마이너스로 되돌려 다시 올려요.",
            fontSize = 12.sp, lineHeight = 18.sp, color = Gray,
        )
        Spacer(Modifier.height(24.dp))
      }
      PrimaryButton("다음") { onNext(fl, bd) }
      Spacer(Modifier.height(24.dp))
    }
}

// ---------------- 4단계: 목표 레벨 ----------------

@Composable
private fun LevelStep(current: Int, kind: LoopKind, onNext: (Int) -> Unit) {
    var level by remember { mutableStateOf(current) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("몇 레벨까지\n올릴까요?", fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (kind == LoopKind.EXPLORE)
                "목표에 닿으면 저절로 멈춰요.\n레벨이 오르면 그 레벨에 맞는 탐험지로 알아서 옮겨 갑니다."
            else
                "목표에 닿으면 저절로 멈춰요.\n7.0 이 깨지면 데이터를 지워 되돌리고 다시 시도합니다.",
            fontSize = 14.sp, lineHeight = 21.sp, color = Gray,
        )

        Spacer(Modifier.height(36.dp))

        Text(
            "Lv.$level",
            fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Blue,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(20, 21, 35, 45).forEach { v ->
                val on = level == v
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) Blue else Field)
                        .clickable { level = v }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$v", fontSize = 16.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Color.White else Ink)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton("−") { if (level > 2) level-- }
            Text("직접 조절", fontSize = 13.sp, color = Gray, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepperButton("+") { if (level < 45) level++ }
        }

        Spacer(Modifier.weight(1f))
        PrimaryButton("다음") { onNext(level) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
            .background(Field).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 22.sp, color = Ink) }
}

// ---------------- 4단계: 확인 ----------------

@Composable
private fun ConfirmStep(repo: DexRepository, setup: LoopSetup, onStart: () -> Unit) {
    val d = setup.dragon
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(d) { d?.let { thumb = withContext(Dispatchers.IO) { repo.thumbnail(it.no) } } }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("이대로 시작할까요?", fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(24.dp))

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(Field).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                thumb?.let { Image(it.asImageBitmap(), d?.name ?: "", Modifier.size(48.dp)) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(d?.name ?: "-", fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "#${d?.no} · ${repo.attrLabel(d?.attr)} · ${repo.typeLabel(d?.type)}",
                        fontSize = 12.sp, color = Gray,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            SummaryLine("루프", setup.kind.title)
            SummaryLine("단계", setup.stage?.label ?: "-")
            SummaryLine("현재", "Lv.${setup.currentLevel} · 등급 ${String.format("%.1f", setup.currentGrade)}")
            SummaryLine("목표 레벨", "Lv.${setup.targetLevel}")
            if (setup.kind == LoopKind.EXPLORE) {
                SummaryLine("등급 하한", "${String.format("%.1f", setup.minGrade)} 아래로 안 내려감")
                SummaryLine("데삭 기준", "마이너스 ${setup.minusBudget} 개")
                SummaryLine("탐험지", "레벨에 맞는 곳으로 자동 이동")
            } else {
                SummaryLine(
                    "사용 아이템",
                    if (setup.stage?.awakened == true) "각성 전용 레벨업 +1" else "일반 레벨업 +1",
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "시작하면 드빌이 실행되고, 화면 위에 작은 창이 떠요.\n그 창에서 진행 상황을 보고 멈출 수 있어요.",
            fontSize = 13.sp, lineHeight = 20.sp, color = Gray,
        )

        Spacer(Modifier.weight(1f))
        PrimaryButton("드빌 실행하고 시작", enabled = d != null && setup.stage != null, onStart)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, fontSize = 14.sp, color = Gray, modifier = Modifier.width(88.dp))
        Text(value, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium)
    }
}

// ---------------- 공통 조각 ----------------

@Composable
fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Blue,
            disabledContainerColor = Line,
            disabledContentColor = Gray,
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun JumpButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(21.dp))
            .background(Color(0xE6191F28)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun FilterRow(
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
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (on) Blue else Field)
                        .clickable { onSelect(if (on && code != null) null else code) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(name, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Color.White else Ink)
                }
            }
        }
    }
}

@Composable
fun DragonRow(
    d: Dragon, thumb: Bitmap?, selected: Boolean,
    attrName: String, typeName: String, onPick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFE8F1FE) else Field)
            .clickable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumb != null) Image(thumb.asImageBitmap(), d.name, Modifier.size(34.dp))
        else Box(Modifier.size(34.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 15.sp, lineHeight = 19.sp, color = Ink)
            Text("#${d.no} · $attrName · $typeName",
                fontSize = 11.sp, lineHeight = 14.sp, color = Gray)
        }
        if (selected) {
            Text("선택됨", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue)
        }
    }
}

val ATTRS = listOf<Pair<String?, String>>(
    null to "전체", "F" to "불", "W" to "바람", "A" to "물", "E" to "땅",
    "L" to "빛", "D" to "어둠", "N" to "무", "DA" to "DA", "DU" to "DU",
)

val TYPES = listOf<Pair<String?, String>>(
    null to "전체", "NONE" to "중립", "H" to "체력", "A" to "공격", "D" to "방어",
    "HA" to "체공", "AD" to "공방", "HD" to "체방",
)
