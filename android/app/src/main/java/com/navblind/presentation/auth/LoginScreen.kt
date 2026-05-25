package com.navblind.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

    // 화면 진입 안내
    LaunchedEffect(Unit) {
        view.announceForAccessibility(
            "NavBlind 시작 화면입니다. 로그인 버튼을 탭하여 시작하세요."
        )
    }

    // 로그인 성공 시 화면 전환
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) onLoginSuccess()
    }

    // 오류 발생 시 즉시 낭독
    LaunchedEffect(uiState.error) {
        uiState.error?.let { view.announceForAccessibility(it) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // 앱 이름 + 부제 — 하나의 설명으로 병합
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

            if (uiState.isLoading) {
                // 로딩 상태 — liveRegion 으로 자동 낭독
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "로그인 중입니다. 잠시 기다려 주세요."
                    }
                ) {
                    CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "로그인 중...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            } else {
                Button(
                    onClick = { viewModel.login(onLoginSuccess) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "로그인하고 NavBlind 시작하기"
                        },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("로그인 / 시작하기", fontSize = 18.sp)
                }
            }

            // 오류 메시지 — Assertive 로 즉시 낭독
            uiState.error?.let { error ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Assertive
                        contentDescription = "오류: $error. 확인 버튼을 탭하세요."
                    }
                )
                TextButton(
                    onClick = viewModel::clearError,
                    modifier = Modifier.semantics { contentDescription = "오류 확인" }
                ) {
                    Text("확인")
                }
            }
        }
    }
}
