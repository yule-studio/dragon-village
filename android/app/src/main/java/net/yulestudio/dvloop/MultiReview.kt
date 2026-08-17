package net.yulestudio.dvloop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 다중 레벨업 — 몇 마리를 걸어둘지 먼저 고른다.
 *
 * 정해두면 그 수만큼 설정을 이어서 묻고, 다 담으면 바로 시작한다.
 * 중간에 그만두고 싶으면 담은 것만으로 시작할 수 있다.
 */
@Composable
fun MultiCount(
    max: Int,
    onPick: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val ink = Color(0xFF111318)
    val gray = Color(0xFF8A9099)
    val blue = Color(0xFF2D7BF6)

    Column(
        Modifier.fillMaxSize().background(Color.White).systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            "←  뒤로",
            fontSize = 15.sp, color = gray,
            modifier = Modifier.clickable { onBack() },
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "몇 마리를\n걸어둘까요?",
            fontSize = 24.sp, lineHeight = 33.sp,
            fontWeight = FontWeight.Bold, color = ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "고른 수만큼 설정을 이어서 물어요.\n" +
                "한 마리가 목표에 닿으면 다음 마리로 저절로 넘어갑니다.",
            fontSize = 14.sp, lineHeight = 21.sp, color = gray,
        )
        Spacer(Modifier.height(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (1..max).forEach { n ->
                Box(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFFDDE1E6), RoundedCornerShape(14.dp))
                        .clickable { onPick(n) }
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$n",
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, color = blue,
                    )
                }
            }
        }
    }
}

/**
 * 다중 레벨업 — 담은 대상을 보여주고 더 담을지 시작할지 고른다.
 *
 * 한 마리씩 걸어놓고 끝날 때까지 기다리면 밤새 한 마리밖에 못 돈다.
 * 세 마리까지 미리 짜두면 첫 마리가 끝나는 대로 다음으로 넘어간다.
 *
 * 셋으로 제한한 것은 그 이상은 앞의 결과에 따라 뒤가 달라져서, 미리
 * 정해두는 것이 오히려 손해이기 때문이다.
 */
@Composable
fun MultiReview(
    picked: List<LoopSetup>,
    max: Int,
    onAdd: () -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val ink = Color(0xFF111318)
    val gray = Color(0xFF8A9099)
    val blue = Color(0xFF2D7BF6)

    Column(
        Modifier.fillMaxSize().background(Color.White).systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            "←  뒤로",
            fontSize = 15.sp, color = gray,
            modifier = Modifier.clickable { onBack() },
        )
        Spacer(Modifier.height(22.dp))
        Text(
            "${picked.size}마리 담았어요",
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "위에서부터 차례로 돌아요. 한 마리가 목표에 닿으면\n" +
                "다음 마리로 저절로 넘어갑니다.",
            fontSize = 14.sp, lineHeight = 21.sp, color = gray,
        )
        Spacer(Modifier.height(24.dp))

        picked.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFF4F5F7))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = blue,
                    modifier = Modifier.width(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "${s.dragon?.name ?: "-"} · ${s.stage?.label ?: "-"}",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Lv.${s.currentLevel} → Lv.${s.targetLevel} · " +
                            "${String.format("%.1f", s.minGrade)} 아래로 안 내려감",
                        fontSize = 13.sp, color = gray,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(10.dp))

        if (picked.size < max) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFFDDE1E6), RoundedCornerShape(14.dp))
                    .clickable { onAdd() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "＋ 대상 더 담기 (${picked.size}/$max)",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = blue,
                )
            }
        } else {
            Text(
                "$max 마리까지 담을 수 있어요.",
                fontSize = 13.sp, color = gray, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(blue)
                .clickable { onStart() }
                .padding(vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "드빌 실행하고 시작",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
