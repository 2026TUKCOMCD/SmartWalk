package com.smartwalker.presentation.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val view = LocalView.current
    val activity = LocalContext.current as ComponentActivity

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onLoginSuccess()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { view.announceForAccessibility("오류: $it") }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "NavBlind. 시각장애인용 스마트글래스 내비게이션"
                }
            ) {
                Text(
                    text = "NavBlind",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clearAndSetSemantics {}
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "시각장애인용 스마트글래스 내비게이션",
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }

            Spacer(Modifier.height(48.dp))

            when (uiState.step) {
                AuthStep.PHONE_INPUT -> PhoneInputStep(
                    phoneNumber = uiState.phoneNumber,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onPhoneChanged = viewModel::onPhoneNumberChanged,
                    onSubmit = { viewModel.startPhoneVerification(activity) },
                    onClearError = viewModel::clearError
                )
                AuthStep.CODE_INPUT -> CodeInputStep(
                    code = uiState.smsCode,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onCodeChanged = viewModel::onCodeChanged,
                    onSubmit = viewModel::submitCode,
                    onBack = viewModel::goBackToPhoneInput,
                    onClearError = viewModel::clearError
                )
            }
        }
    }
}

@Composable
private fun PhoneInputStep(
    phoneNumber: String,
    isLoading: Boolean,
    error: String?,
    onPhoneChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onClearError: () -> Unit
) {
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        view.announceForAccessibility("전화번호 입력 화면입니다. 전화번호를 입력하세요.")
        focusRequester.requestFocus()
    }

    OutlinedTextField(
        value = phoneNumber,
        onValueChange = onPhoneChanged,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics { contentDescription = "전화번호 입력. 010으로 시작하는 번호를 입력하세요." },
        label = { Text("전화번호") },
        placeholder = { Text("010-0000-0000") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        singleLine = true,
        enabled = !isLoading
    )

    Spacer(Modifier.height(16.dp))

    if (isLoading) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "인증번호 발송 중입니다. 잠시 기다려 주세요."
            }
        ) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(8.dp))
            Text("인증번호 발송 중...", modifier = Modifier.clearAndSetSemantics {})
        }
    } else {
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "인증번호 받기. 탭하면 SMS 인증번호가 발송됩니다."
                },
            shape = MaterialTheme.shapes.medium
        ) {
            Text("인증번호 받기", fontSize = 18.sp)
        }
    }

    error?.let { msg ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = msg,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = "오류: $msg"
            }
        )
        TextButton(
            onClick = onClearError,
            modifier = Modifier.semantics { contentDescription = "오류 확인" }
        ) { Text("확인") }
    }
}

@Composable
private fun CodeInputStep(
    code: String,
    isLoading: Boolean,
    error: String?,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onClearError: () -> Unit
) {
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        view.announceForAccessibility("인증번호 입력 화면입니다. 문자로 받은 6자리 숫자를 입력하세요.")
        focusRequester.requestFocus()
    }

    Text(
        text = "SMS로 발송된 인증번호를 입력하세요",
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { contentDescription = "SMS로 발송된 6자리 인증번호를 입력하세요." }
    )

    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = code,
        onValueChange = onCodeChanged,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .semantics { contentDescription = "인증번호 입력. 6자리 숫자를 입력하세요. 현재 ${code.length}자리 입력됨." },
        label = { Text("인증번호 6자리") },
        placeholder = { Text("000000") },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        singleLine = true,
        enabled = !isLoading
    )

    Spacer(Modifier.height(16.dp))

    if (isLoading) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "인증 확인 중입니다."
            }
        ) {
            CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
            Spacer(Modifier.height(8.dp))
            Text("인증 확인 중...", modifier = Modifier.clearAndSetSemantics {})
        }
    } else {
        Button(
            onClick = onSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "인증하기. 탭하면 인증번호를 확인합니다."
                },
            shape = MaterialTheme.shapes.medium
        ) {
            Text("인증하기", fontSize = 18.sp)
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "전화번호 다시 입력. 탭하면 전화번호 입력 화면으로 돌아갑니다." }
        ) {
            Text("전화번호 다시 입력")
        }
    }

    error?.let { msg ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = msg,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = "오류: $msg"
            }
        )
        TextButton(
            onClick = onClearError,
            modifier = Modifier.semantics { contentDescription = "오류 확인" }
        ) { Text("확인") }
    }
}
