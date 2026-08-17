package net.yulestudio.dvloop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 무엇을 돌릴지 고르는 메뉴.
 *
 * 지금 동작하는 것은 레벨업 루프뿐이다. 나머지는 아직 무엇을 어떻게 할지
 * 정해지지 않았으므로 '준비 중' 으로 두고 누를 수 없게 한다.
 * 목록에서 빼버리면 앞으로 무엇이 생길지 알 수 없고,
 * 멀쩡한 것처럼 두면 눌러보고 실패한다.
 */
enum class LoopKind(
    val title: String,
    val desc: String,
    val ready: Boolean,
) {
    LEVEL_UP(
        "레벨업 루프",
        "등급 하한을 지키며 목표 레벨까지 올려요",
        true,
    ),
    MULTI_LEVEL_UP(
        "다중 레벨업",
        "최대 3마리를 한 번에 걸어두고 차례로 올려요",
        true,
    ),
    EXPLORE(
        "탐험 루프",
        "등급 하한을 지키며 탐험으로 레벨을 올려요",
        true,
    ),
    MISSION(
        "미션 루프",
        "정기 주고받기 · 출석체크 · 보물상자를 대신 해요",
        true,
    ),
    // 아르하는 셋으로 나눠 만들고 마지막에 하나로 묶는다. 한 덩어리로 만들면
    // 어디가 틀렸는지 못 찾는다 — 미션 루프에서 겪었다.
    ARHA_SEAL(
        "아르하 · 봉인지 탐험",
        "봉인지를 반복해 부활회복제를 모아요",
        false,
    ),
    ARHA_BOSS(
        "아르하 · 보스 공략",
        "블리자드 · 다크나이트를 잡아요",
        false,
    ),
    ARHA_TOWER(
        "아르하 · 증명의 탑",
        "증명의 탑을 올라요",
        false,
    ),
    ARHA(
        "아르하 종합",
        "봉인지 → 보스 → 증명의 탑을 이어서 돌아요",
        false,
    ),
    PALACE(
        "왕궁 본체 루프",
        "왕궁 본체를 반복해서 잡아요",
        false,
    ),
}

@Composable
fun LoopMenu(
    creds: Credentials,
    canOverlay: Boolean,
    saved: SavedLoop?,
    onOverlaySetting: () -> Unit,
    onResume: () -> Unit,
    onNewSetup: () -> Unit,
    onAddNext: () -> Unit,
    onPick: (LoopKind) -> Unit,
    onLogout: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    onClick = onLogout,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) { Text("로그아웃", color = Gray, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(20.dp))

            if (!canOverlay) {
                OverlayNotice(onOverlaySetting)
                Spacer(Modifier.height(20.dp))
            }

            if (saved != null) {
                SavedCard(saved, canOverlay, onResume, onNewSetup, onAddNext)
                Spacer(Modifier.height(24.dp))
            }

            Text("무엇을 돌릴까요?", fontSize = 18.sp,
                fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(4.dp))
        }

        items(LoopKind.entries) { kind ->
            LoopCard(
                kind = kind,
                enabled = kind.ready && canOverlay,
                onClick = { onPick(kind) },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                if (!canOverlay)
                    "미니창 권한을 먼저 켜야 루프를 시작할 수 있어요."
                else
                    "나머지 루프는 준비되는 대로 열려요.",
                fontSize = 12.sp, color = Gray,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 지난번에 하던 설정. 5단계를 다시 밟지 않고 한 번에 재개한다. */
data class SavedLoop(
    val dragonName: String,
    val stageLabel: String,
    val level: Int,
    val targetLevel: Int,
    val grade: Double,
    val done: Boolean,
    val kindTitle: String,
    /** 탐험 루프의 등급 하한. 레벨업 루프는 null 이다. */
    val minGrade: Double?,
    /** 이 대상 다음에 이어서 키울 것들. 없으면 빈 목록. */
    val queue: List<String> = emptyList(),
)

@Composable
private fun SavedCard(
    s: SavedLoop,
    canOverlay: Boolean,
    onResume: () -> Unit,
    onNewSetup: () -> Unit,
    onAddNext: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (s.done) Field else Color(0xFFE8F1FE))
            .padding(18.dp),
    ) {
        Text(
            (if (s.done) "지난 목표를 끝냈어요" else "하던 작업이 있어요") + " · ${s.kindTitle}",
            fontSize = 13.sp, color = Gray,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${s.dragonName} · ${s.stageLabel}",
            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink,
        )
        Spacer(Modifier.height(2.dp))
        // 두 루프는 등급을 다루는 방식이 다르다.
        // 레벨업은 정해진 등급을 그대로 지키고, 탐험은 하한 아래로만 안 내려가면 된다.
        Text(
            "Lv.${s.level} → Lv.${s.targetLevel} · " +
                if (s.minGrade != null)
                    "등급 ${String.format("%.1f", s.minGrade)} 아래로 안 내려가게"
                else
                    "등급 ${String.format("%.1f", s.grade)} 유지",
            fontSize = 13.sp, color = Gray,
        )
        if (s.queue.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "다음 차례 · " + s.queue.joinToString(" → "),
                fontSize = 12.sp, lineHeight = 18.sp, color = Blue,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!s.done) {
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canOverlay) Blue else Line)
                        .clickable(enabled = canOverlay, onClick = onResume),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("이어서 시작", fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canOverlay) Color.White else Gray)
                }
            }
            Box(
                Modifier.weight(1f).height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, Line, RoundedCornerShape(12.dp))
                    .clickable(onClick = onNewSetup),
                contentAlignment = Alignment.Center,
            ) {
                Text("새로 설정", fontSize = 15.sp, color = Ink)
            }
        }
        Spacer(Modifier.height(8.dp))
        // 지금 대상이 끝나면 이어서 키울 드래곤을 줄 세운다.
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable(onClick = onAddNext),
            contentAlignment = Alignment.Center,
        ) {
            Text("＋ 다음 대상 추가", fontSize = 14.sp, color = Blue)
        }
    }
}

@Composable
private fun LoopCard(kind: LoopKind, enabled: Boolean, onClick: () -> Unit) {
    // 파란 테두리에 파란 배경은 '고른 것' 으로 읽힌다.
    // 여기서는 아무것도 고른 상태가 아니라 열려 있을 뿐이므로 흰 바탕에 얇은 선만 둔다.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Color.White else Field)
            .then(
                if (enabled) Modifier.border(1.dp, Line, RoundedCornerShape(14.dp))
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kind.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) Ink else Gray,
                )
                if (!kind.ready) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "준비 중",
                        fontSize = 11.sp, color = Gray,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Line)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(kind.desc, fontSize = 13.sp, color = Gray)
        }
        if (enabled) {
            Text("→", fontSize = 18.sp, color = Blue)
        }
    }
}

@Composable
fun OverlayNotice(onClick: () -> Unit) {
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
