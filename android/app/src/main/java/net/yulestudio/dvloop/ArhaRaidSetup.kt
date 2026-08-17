package net.yulestudio.dvloop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RInk = Color(0xFF111318)
private val RGray = Color(0xFF8A9099)
private val RBlue = Color(0xFF2D7BF6)
private val RLine = Color(0xFFDDE1E6)

/**
 * 아르하 레이드 루프 설정.
 *
 * 레이드는 **수동 탐험을 해야 나온다.** 자동 탐험으로는 안 걸린다.
 * 그래서 어느 봉인지에서 돌지와, 몬스터가 나왔을 때 3마리 편성에 쓸 속성을
 * 미리 정해둔다. 봉인지마다 그리고 가진 드래곤에 따라 유리한 조합이 달라서
 * 빛+불로 고정해두면 다른 봉인지에서 불리해진다.
 */
@Composable
fun ArhaRaidSetup(
    onExit: () -> Unit,
    onStart: (ArhaMap.Seal, List<ArhaMap.Element>) -> Unit,
) {
    var seal by remember { mutableStateOf(ArhaMap.Seal.LIGHT) }
    var picks by remember { mutableStateOf(listOf(ArhaMap.Element.LIGHT, ArhaMap.Element.FIRE)) }

    Column(
        Modifier.fillMaxSize().background(Color.White).systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text("←  뒤로", fontSize = 15.sp, color = RGray,
            modifier = Modifier.clickable { onExit() })
        Spacer(Modifier.height(20.dp))
        Text("아르하 레이드", fontSize = 24.sp,
            fontWeight = FontWeight.Bold, color = RInk)
        Spacer(Modifier.height(8.dp))
        Text(
            "레이드는 수동 탐험을 해야 나와요.\n" +
                "정한 봉인지에서 탐험을 반복하다가, 레이드가 뜨면 멈춰요.",
            fontSize = 14.sp, lineHeight = 21.sp, color = RGray,
        )

        Spacer(Modifier.height(26.dp))
        Text("어느 봉인지에서 돌까요?", fontSize = 13.sp,
            color = RGray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        ArhaMap.Seal.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { s ->
                    val on = s == seal
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) RBlue else Color.White)
                            .border(1.dp, if (on) RBlue else RLine, RoundedCornerShape(12.dp))
                            .clickable { seal = s }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.label, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                color = if (on) Color.White else RInk)
                            Spacer(Modifier.height(2.dp))
                            Text(s.dragon, fontSize = 12.sp,
                                color = if (on) Color.White.copy(alpha = 0.85f) else RGray)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "몬스터가 나오면 세울 속성 (최대 2개)",
            fontSize = 13.sp, color = RGray, fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "탐험 중 몬스터 전투에서 이 속성으로 골라요. 나머지 한 자리는 지금 용이 들어가요.",
            fontSize = 12.sp, lineHeight = 18.sp, color = RGray,
        )
        Spacer(Modifier.height(10.dp))
        ArhaMap.Element.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { e ->
                    val on = e in picks
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (on) RBlue else Color.White)
                            .border(1.dp, if (on) RBlue else RLine, RoundedCornerShape(12.dp))
                            .clickable {
                                // 두 개까지만. 이미 두 개면 가장 먼저 고른 것을 밀어낸다.
                                picks = when {
                                    on -> picks - e
                                    picks.size < 2 -> picks + e
                                    else -> picks.drop(1) + e
                                }
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e.label, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = if (on) Color.White else RInk)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.weight(1f))
        Text(
            "${seal.label} · ${if (picks.isEmpty()) "속성 미지정" else picks.joinToString(" + ") { it.label }}",
            fontSize = 13.sp, color = RGray,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (picks.isEmpty()) RLine else RBlue)
                .clickable(enabled = picks.isNotEmpty()) { onStart(seal, picks) }
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("드빌 실행하고 시작", fontSize = 17.sp,
                fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.height(20.dp))
    }
}
