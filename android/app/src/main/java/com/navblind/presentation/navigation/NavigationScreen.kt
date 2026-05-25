package com.navblind.presentation.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.navblind.domain.model.SearchResult
import com.navblind.domain.model.TurnModifier

@Composable
fun NavigationScreen(
    viewModel: NavigationViewModel = hiltViewModel(),
    detectionViewModel: DetectionViewModel = hiltViewModel(),
    recordingViewModel: RecordingViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val detectionState by detectionViewModel.uiState.collectAsState()
    val alertMessage by detectionViewModel.alertMessage.collectAsState()
    val isRecording by recordingViewModel.isRecording.collectAsState()
    val view = LocalView.current

    // 오류 발생 시 TalkBack 에 즉시 알림
    LaunchedEffect(uiState.error) {
        uiState.error?.let { view.announceForAccessibility(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isNavigating -> {
                NavigatingView(
                    uiState = uiState,
                    detectionState = detectionState,
                    onStop = viewModel::stopNavigation,
                    onRepeat = viewModel::repeatCurrentInstruction
                )
            }
            else -> {
                SearchView(
                    searchQuery = uiState.searchQuery,
                    searchResults = searchResults,
                    isSearching = uiState.isSearching,
                    isLoading = uiState.isLoading,
                    isRecording = isRecording,
                    detectionLog = detectionState.detectionLog,
                    onSearchQueryChange = { query ->
                        if (query.length >= 2) viewModel.searchDestination(query)
                    },
                    onVoiceInput = viewModel::startVoiceInput,
                    onDestinationSelected = viewModel::startNavigation,
                    onClearSearch = viewModel::clearSearchResults,
                    onToggleRecording = recordingViewModel::toggleRecording,
                    onNavigateToSettings = onNavigateToSettings
                )
            }
        }

        // 장애물 경고 오버레이 — liveRegion 으로 TalkBack 자동 낭독
        AnimatedVisibility(
            visible = alertMessage.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC000000)),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite   // 변경 시 자동 낭독
                    contentDescription = alertMessage
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 장식 아이콘 — 접근성 트리 제외
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFEB3B),
                        modifier = Modifier
                            .size(20.dp)
                            .clearAndSetSemantics {}
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = alertMessage,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {}  // 부모 Card 가 낭독
                    )
                }
            }
        }

        // 오류 스낵바 — Assertive 로 즉시 낭독
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
                action = {
                    TextButton(onClick = viewModel::clearError) { Text("확인") }
                }
            ) { Text(error) }
        }

        // 경로 탐색 중 로딩 오버레이
        if (uiState.isLoading || uiState.isRerouting) {
            val loadingMsg = if (uiState.isRerouting) "경로 재탐색 중" else "경로 탐색 중"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .semantics { contentDescription = loadingMsg },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clearAndSetSemantics {}  // 부모가 낭독
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "$loadingMsg...", color = Color.White, fontSize = 18.sp)
                }
            }
        }

        // 도착 다이얼로그
        if (uiState.hasArrived) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("도착") },
                text = { Text("목적지에 도착했습니다!") },
                confirmButton = {
                    Button(
                        onClick = viewModel::stopNavigation,
                        modifier = Modifier.semantics { contentDescription = "확인. 안내를 종료합니다" }
                    ) { Text("확인") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchView(
    searchQuery: String,
    searchResults: List<SearchResult>,
    isSearching: Boolean,
    isLoading: Boolean,
    isRecording: Boolean,
    detectionLog: List<DetectionLogEntry>,
    onSearchQueryChange: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onDestinationSelected: (SearchResult) -> Unit,
    onClearSearch: () -> Unit,
    onToggleRecording: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    var localQuery by remember { mutableStateOf(searchQuery) }
    val view = LocalView.current

    // 화면 진입 안내
    LaunchedEffect(Unit) {
        view.announceForAccessibility(
            "NavBlind 검색 화면입니다. 목적지를 입력하거나 음성 버튼을 사용하세요."
        )
    }

    LaunchedEffect(searchQuery) { localQuery = searchQuery }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 앱 타이틀 — 장식 텍스트, 포커스 생략
            Text(
                text = "NavBlind",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics {}
            )
            RecordingButton(isRecording = isRecording, onClick = onToggleRecording)
            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier.semantics { contentDescription = "설정 화면 열기" }
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        }

        OutlinedTextField(
            value = localQuery,
            onValueChange = {
                localQuery = it
                onSearchQueryChange(it)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "목적지 검색. 두 글자 이상 입력하면 자동 검색됩니다." },
            placeholder = { Text(if (isLoading) "경로 탐색 중..." else "목적지를 입력하세요") },
            enabled = !isLoading,
            leadingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .clearAndSetSemantics {},
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Search, contentDescription = null,
                        modifier = Modifier.clearAndSetSemantics {})
                }
            },
            trailingIcon = {
                Row {
                    if (localQuery.isNotEmpty() && !isLoading) {
                        IconButton(
                            onClick = { localQuery = ""; onClearSearch() },
                            modifier = Modifier.semantics { contentDescription = "검색어 지우기" }
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                    IconButton(
                        onClick = onVoiceInput,
                        enabled = !isLoading,
                        modifier = Modifier.semantics { contentDescription = "음성으로 목적지 입력" }
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchQueryChange(localQuery) }),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onVoiceInput,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .semantics { contentDescription = "음성으로 목적지 말하기. 탭 후 말씀하세요." },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("음성으로 목적지 말하기", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "검색 중입니다. 잠시 기다려 주세요." },
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
            }
        } else if (searchResults.isNotEmpty()) {
            Text(
                text = "검색 결과 ${searchResults.size}건",
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }  // 결과 수 자동 낭독
            )
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(searchResults) { result ->
                    SearchResultItem(result = result, onClick = { onDestinationSelected(result) })
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // 감지 로그는 시각적 디버그 패널 — TalkBack 에서 숨김
        if (detectionLog.isNotEmpty()) {
            DetectionLogPanel(
                log = detectionLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun SearchResultItem(result: SearchResult, onClick: () -> Unit) {
    val description = buildString {
        append("목적지: ${result.name}")
        result.address?.let { append(". $it") }
        result.distanceFormatted?.let { append(". ${it} 거리") }
        append(". 탭하면 안내를 시작합니다.")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClickLabel = "안내 시작", onClick = onClick)
            // clearAndSetSemantics 로 카드 전체를 하나의 설명으로 병합
            .clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Place, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                result.address?.let {
                    Text(text = it, fontSize = 14.sp, color = Color.Gray, maxLines = 2)
                }
            }
            result.distanceFormatted?.let {
                Text(text = it, fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NavigatingView(
    uiState: NavigationUiState,
    detectionState: DetectionUiState,
    onStop: () -> Unit,
    onRepeat: () -> Unit
) {
    val view = LocalView.current

    // 화면 진입 안내
    LaunchedEffect(Unit) {
        view.announceForAccessibility("안내 중입니다. 화면 아래 다시 듣기 또는 종료 버튼을 이용하세요.")
    }

    // 안내 지시 변경 시 TalkBack 안내 (이미 TTS 로 읽히지만, TalkBack 포커스 이동 보조)
    LaunchedEffect(uiState.currentInstruction?.text) {
        uiState.currentInstruction?.text?.let {
            view.announceForAccessibility(it)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 디버그 패널 — TalkBack 에서 완전히 숨김
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .clearAndSetSemantics {},
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("[DEBUG] 위치 정보", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "GPS: ${uiState.gpsPosition?.let {
                        "%.6f, %.6f (±%.1fm)".format(it.coordinate.latitude, it.coordinate.longitude, it.accuracy)
                    } ?: "없음"}",
                    color = Color.Cyan, fontSize = 11.sp
                )
                Text(
                    text = "VPS: ${uiState.vpsPosition?.let {
                        "%.6f, %.6f (±%.1fm)".format(it.coordinate.latitude, it.coordinate.longitude, it.accuracy)
                    } ?: "없음"}",
                    color = Color.Green, fontSize = 11.sp
                )
                Text(
                    text = "KF : ${uiState.currentPosition?.let {
                        "%.6f, %.6f (±%.1fm)".format(it.coordinate.latitude, it.coordinate.longitude, it.accuracy)
                    } ?: "없음"}",
                    color = Color.White, fontSize = 11.sp
                )
            }
        }

        // 장애물 경고 — liveRegion 으로 자동 낭독 (TTS 와 별개로 TalkBack 보조)
        detectionState.mostDangerous?.let { obj ->
            if (obj.dangerLevel >= 0.5f) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Assertive
                            contentDescription = "주의. ${obj.toWarningMessage()}"
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (obj.dangerLevel >= 0.8f)
                            Color(0xFFB71C1C).copy(alpha = 0.9f)
                        else Color(0xFFE65100).copy(alpha = 0.85f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clearAndSetSemantics {},  // 부모 Card 가 낭독
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = obj.toWarningMessage(), color = Color.White,
                            fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 목적지 이름 + 남은 거리를 하나로 병합
        val remainingDesc = uiState.remainingDistance?.let { d ->
            if (d < 1000) "${d}미터" else "${"%.1f".format(d / 1000.0)}킬로미터"
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(top = 32.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append("목적지: ${uiState.destination?.name ?: "목적지"}")
                        remainingDesc?.let { append(". 남은 거리 $it") }
                    }
                }
        ) {
            Text(
                text = uiState.destination?.name ?: "목적지",
                fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics {}
            )
            uiState.remainingDistance?.let { distance ->
                Text(
                    text = if (distance < 1000) "${distance}m"
                           else String.format("%.1fkm", distance / 1000.0),
                    fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .clearAndSetSemantics {}
                )
                Text(
                    text = "남음", fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 현재 안내 지시 카드
        uiState.currentInstruction?.let { instruction ->
            val directionLabel = when (instruction.modifier) {
                TurnModifier.LEFT -> "좌회전"
                TurnModifier.SLIGHT_LEFT -> "왼쪽 방향"
                TurnModifier.RIGHT -> "우회전"
                TurnModifier.SLIGHT_RIGHT -> "오른쪽 방향"
                TurnModifier.UTURN -> "유턴"
                else -> "직진"
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
                    // 카드 전체를 하나의 안내 문장으로 읽힘
                    .clearAndSetSemantics {
                        contentDescription = "현재 안내: $directionLabel. ${instruction.text}"
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val directionIcon = when (instruction.modifier) {
                        TurnModifier.LEFT,
                        TurnModifier.SLIGHT_LEFT -> Icons.Default.TurnLeft
                        TurnModifier.RIGHT,
                        TurnModifier.SLIGHT_RIGHT -> Icons.Default.TurnRight
                        TurnModifier.UTURN -> Icons.Default.UTurnLeft
                        else -> Icons.Default.ArrowUpward
                    }
                    Icon(directionIcon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = instruction.text, fontSize = 20.sp,
                        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 조작 버튼 행
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onRepeat,
                containerColor = Color.White,
                modifier = Modifier
                    .size(64.dp)
                    .semantics { contentDescription = "안내 다시 듣기" }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            }

            FloatingActionButton(
                onClick = onStop,
                containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(80.dp)
                    .semantics { contentDescription = "안내 종료" },
                shape = CircleShape
            ) {
                Icon(Icons.Default.Close, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // 감지 로그 패널 — TalkBack 에서 숨김 (TTS 로 이미 안내됨)
        if (detectionState.detectionLog.isNotEmpty()) {
            DetectionLogPanel(
                log = detectionState.detectionLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .padding(bottom = 8.dp)
                    .clearAndSetSemantics {}
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun RecordingButton(isRecording: Boolean, onClick: () -> Unit) {
    val recordingRed = Color(0xFFE53935)
    val label = if (isRecording) "데이터 수집 중단" else "데이터 수집 시작"

    if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(600), repeatMode = RepeatMode.Reverse
            ),
            label = "rec_dot_alpha"
        )
        OutlinedButton(
            onClick = onClick,
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(recordingRed)
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = "수집 중"
            }
        ) {
            Box(modifier = Modifier
                .size(8.dp)
                .alpha(alpha)
                .background(recordingRed, CircleShape)
                .clearAndSetSemantics {})
            Spacer(Modifier.width(6.dp))
            Text(text = "수집 중", fontSize = 13.sp, color = recordingRed,
                modifier = Modifier.clearAndSetSemantics {})
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(40.dp)
                .border(1.dp, Color.LightGray, CircleShape)
                .semantics { contentDescription = label }
        ) {
            Icon(Icons.Default.FiberManualRecord, contentDescription = null,
                tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DetectionLogPanel(log: List<DetectionLogEntry>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xDD000000))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.List, contentDescription = null,
                    tint = Color(0xFF80CBC4), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("감지 로그  (최근 ${log.size}건)", color = Color(0xFF80CBC4),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Divider(color = Color.White.copy(alpha = 0.15f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(2.dp))
            LazyColumn(state = rememberLazyListState()) {
                items(log) { entry -> DetectionLogRow(entry) }
            }
        }
    }
}

@Composable
private fun DetectionLogRow(entry: DetectionLogEntry) {
    val dangerColor = when {
        entry.dangerLevel >= 0.8f -> Color(0xFFEF5350)
        entry.dangerLevel >= 0.5f -> Color(0xFFFF9800)
        else -> Color(0xFF81C784)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(entry.time, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(56.dp))
        Text(entry.className, color = Color.White, fontSize = 11.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp))
        Text(entry.distance, color = Color(0xFF80DEEA), fontSize = 11.sp, modifier = Modifier.width(38.dp))
        Text(entry.direction, color = Color(0xFFCE93D8), fontSize = 11.sp, modifier = Modifier.width(30.dp))
        Text("%.0f%%".format(entry.confidence * 100), color = Color.Gray,
            fontSize = 10.sp, modifier = Modifier.width(32.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(entry.dangerLevel.coerceIn(0f, 1f))
                    .background(dangerColor.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
            )
        }
    }
}
