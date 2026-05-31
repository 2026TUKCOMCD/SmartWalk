package com.smartwalker.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pref = uiState.preference
    val view = LocalView.current

    // 화면 진입 안내
    LaunchedEffect(Unit) {
        view.announceForAccessibility("환경설정 화면입니다. 위아래로 스와이프하여 설정 항목을 탐색하세요.")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("환경설정") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "뒤로 가기" }
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (pref == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "설정을 불러오는 중입니다." },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
                }
                return@Column
            }

            // 음성 속도 슬라이더
            AccessibleSlider(
                label = "음성 속도",
                valueDescription = "${"%.1f".format(pref.speechRate)}배속",
                value = pref.speechRate,
                range = 0.5f..2.0f,
                onChanged = viewModel::updateSpeechRate,
                hint = "왼쪽으로 밀면 느려지고 오른쪽으로 밀면 빨라집니다."
            )

            // 장애물 경보 거리 슬라이더
            AccessibleSlider(
                label = "장애물 경보 거리",
                valueDescription = "${"%.0f".format(pref.alertDistanceMeters)}미터",
                value = pref.alertDistanceMeters,
                range = 1f..8f,
                onChanged = viewModel::updateAlertDistance,
                hint = "장애물이 이 거리 이내에 있을 때 경보합니다."
            )

            // 진동 알림 토글
            AccessibleSwitch(
                label = "진동 알림",
                checked = pref.vibrationEnabled,
                onChanged = viewModel::updateVibration,
                hint = "장애물 경보 시 진동 피드백을 사용합니다."
            )

            // 계단 회피 토글
            AccessibleSwitch(
                label = "계단 회피 경로",
                checked = pref.avoidStairs,
                onChanged = viewModel::updateAvoidStairs,
                hint = "계단을 피하는 경로를 우선 안내합니다."
            )
        }
    }
}

/**
 * 접근성 속성이 적용된 슬라이더.
 * TalkBack 에서 "라벨. 현재 값. 힌트" 형태로 읽힙니다.
 */
@Composable
private fun AccessibleSlider(
    label: String,
    valueDescription: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChanged: (Float) -> Unit,
    hint: String = ""
) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = buildString {
                append(label)
                append(". 현재 값: $valueDescription")
                if (hint.isNotEmpty()) append(". $hint")
            }
        }
    ) {
        Text(
            text = "$label: $valueDescription",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {}  // 부모가 낭독
        )
        Slider(
            value = value,
            onValueChange = onChanged,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Switch   // 슬라이더 역할 명시
                    contentDescription = label
                    stateDescription = valueDescription
                }
        )
    }
}

/**
 * 접근성 속성이 적용된 스위치 토글.
 * TalkBack 에서 "라벨. 켜짐/꺼짐. 힌트. 탭하여 전환" 형태로 읽힙니다.
 */
@Composable
private fun AccessibleSwitch(
    label: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    hint: String = ""
) {
    val stateText = if (checked) "켜짐" else "꺼짐"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                contentDescription = buildString {
                    append(label)
                    if (hint.isNotEmpty()) append(". $hint")
                }
                stateDescription = stateText
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {}
        )
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            modifier = Modifier.clearAndSetSemantics {}  // 부모 Row 가 낭독
        )
    }
}
