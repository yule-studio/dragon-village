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
        "7.0 을 지키며 목표 레벨까지 올려요",
        true,
    ),
    MISSION(
        "미션 루프",
        "일일·주간 미션을 대신 돌아요",
        false,
    ),
    ARHA(
        "아르하 루프",
        "아르하를 반복해서 돌아요",
        false,
    ),
    PALACE(
        "왕궁 본체 루프",
        "왕궁 본체를 반복해서 잡아요",
        false,
    ),
    EXPLORE(
        "탐험 루프",
        "탐험을 자동으로 보내요",
        false,
    ),
}

@Composable
fun LoopMenu(
    creds: Credentials,
    canOverlay: Boolean,
    onOverlaySetting: () -> Unit,
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

@Composable
private fun LoopCard(kind: LoopKind, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0xFFE8F1FE) else Field

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(
                if (enabled) Modifier.border(1.5.dp, Blue, RoundedCornerShape(14.dp))
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
