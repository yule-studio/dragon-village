package net.yulestudio.dvloop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 로그인 화면. 게임과 같은 2단계 흐름을 따른다.
 *
 *   1단계  로그인 수단 고르기 (하이브로만 활성, 나머지는 왜 안 되는지 밝히고 비활성)
 *   2단계  하이브로 아이디/비밀번호 입력
 *
 * 설계 기준(토스): 한 화면에 한 가지 일만. 큰 글씨, 큰 버튼, 군더더기 없는 문구.
 */

// 색상은 SetupFlow.kt 에 공용으로 둔다

@Composable
fun LoginScreen(
    initialId: String = "",
    onLogin: (id: String, pw: String, remember: Boolean) -> Unit,
) {
    var step by remember { mutableStateOf(0) }

    BackHandler(enabled = step == 1) { step = 0 }

    when (step) {
        0 -> ProviderStep(onHighbrow = { step = 1 })
        else -> HighbrowStep(initialId = initialId, onBack = { step = 0 }, onLogin = onLogin)
    }
}

// ---------------- 1단계: 로그인 수단 ----------------

@Composable
private fun ProviderStep(onHighbrow: () -> Unit) {
    // 문구는 위, 버튼은 아래. 엄지가 닿는 자리에 행동을 둔다.
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "드빌 매크로를\n시작할게요",
            fontSize = 28.sp, lineHeight = 38.sp,
            fontWeight = FontWeight.Bold, color = Ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "드빌에서 쓰던 계정으로 로그인하면\n드래곤을 골라 바로 레벨업을 시작할 수 있어요.",
            fontSize = 15.sp, lineHeight = 23.sp, color = Gray,
        )

        Spacer(Modifier.weight(1f))

        ProviderButton(
            label = "하이브로 아이디로 로그인",
            enabled = true,
            onClick = onHighbrow,
        )
        Spacer(Modifier.height(12.dp))
        ProviderButton(label = "구글 계정으로 로그인", enabled = false)
        Spacer(Modifier.height(12.dp))
        ProviderButton(label = "페이스북으로 로그인", enabled = false)

        Spacer(Modifier.height(20.dp))
        Text(
            "지금은 하이브로 계정만 지원해요.\n다른 계정으로 접속하면 엉뚱한 데이터가 열릴 수 있어\n막아두었어요.",
            fontSize = 12.sp, lineHeight = 19.sp, color = Gray,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ProviderButton(label: String, enabled: Boolean, onClick: () -> Unit = {}) {
    val bg = if (enabled) Blue else Color.White
    val fg = if (enabled) Color.White else Gray

    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(bg, RoundedCornerShape(14.dp))
            .then(
                if (enabled) Modifier
                else Modifier.border(1.dp, Line, RoundedCornerShape(14.dp))
            )
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
        if (!enabled) {
            Spacer(Modifier.width(8.dp))
            Text("준비 중", fontSize = 12.sp, color = Gray)
        }
    }
}

// ---------------- 2단계: 하이브로 로그인 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighbrowStep(
    initialId: String,
    onBack: () -> Unit,
    onLogin: (String, String, Boolean) -> Unit,
) {
    var id by remember { mutableStateOf(initialId) }
    var pw by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(true) }
    var pwVisible by remember { mutableStateOf(false) }

    val pwFocus = remember { FocusRequester() }
    val canSubmit = id.isNotBlank() && pw.isNotBlank()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
            Text("← 뒤로", fontSize = 15.sp, color = Gray)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "하이브로 계정을\n입력해 주세요",
            fontSize = 26.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.Bold, color = Ink,
        )

        Spacer(Modifier.height(36.dp))

        Text("아이디", fontSize = 13.sp, color = Gray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        TossField(
            value = id,
            onValueChange = { id = it },
            placeholder = "하이브로 아이디",
            imeAction = ImeAction.Next,
            // 키보드의 '다음' 으로 비밀번호 칸으로 넘어간다.
            // 키보드가 가린 칸을 직접 누르려다 아이디 칸에 이어 입력되는 사고를 막는다.
            onImeAction = { pwFocus.requestFocus() },
        )

        Spacer(Modifier.height(16.dp))

        Text("비밀번호", fontSize = 13.sp, color = Gray, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        TossField(
            value = pw,
            onValueChange = { pw = it },
            placeholder = "비밀번호",
            imeAction = ImeAction.Done,
            visual = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
            focusRequester = pwFocus,
            onImeAction = { if (canSubmit) onLogin(id.trim(), pw, save) },
            trailing = {
                TextButton(onClick = { pwVisible = !pwVisible }) {
                    Text(if (pwVisible) "숨기기" else "보기", fontSize = 13.sp, color = Gray)
                }
            },
        )

        Spacer(Modifier.height(20.dp))

        // 체크박스는 한 줄, 옆 문구는 두 줄이라 가운데 정렬하면 어긋나 보인다.
        // 체크박스를 첫 줄 높이에 맞추고, 줄 전체를 눌러도 토글되게 한다.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { save = !save }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = save,
                onCheckedChange = { save = it },
                colors = CheckboxDefaults.colors(checkedColor = Blue),
                modifier = Modifier.size(24.dp).offset(y = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "이 기기에 저장",
                    fontSize = 15.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium, color = Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "데이터를 지웠다 다시 로그인할 때 자동으로 입력돼요",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Gray,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onLogin(id.trim(), pw, save) },
            enabled = canSubmit,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Blue,
                disabledContainerColor = Line,
                disabledContentColor = Gray,
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text("로그인", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TossField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    visual: VisualTransformation = VisualTransformation.None,
    focusRequester: FocusRequester? = null,
    onImeAction: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = Gray, fontSize = 16.sp) },
        singleLine = true,
        visualTransformation = visual,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        textStyle = TextStyle(fontSize = 16.sp, color = Ink),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Field,
            unfocusedContainerColor = Field,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Blue,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    )
}
