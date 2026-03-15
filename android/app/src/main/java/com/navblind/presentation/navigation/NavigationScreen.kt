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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.navblind.domain.model.SearchResult

@Composable
fun NavigationScreen(
    viewModel: NavigationViewModel = hiltViewModel(),
    detectionViewModel: DetectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val detectionState by detectionViewModel.uiState.collectAsState()
    val alertMessage by detectionViewModel.alertMessage.collectAsState()

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
                    isRecording = uiState.isRecording,
                    detectionLog = detectionState.detectionLog,
                    onSearchQueryChange = { query ->
                        if (query.length >= 2) {
                            viewModel.searchDestination(query)
                        }
                    },
                    onVoiceInput = viewModel::startVoiceInput,
                    onDestinationSelected = viewModel::startNavigation,
                    onClearSearch = viewModel::clearSearchResults,
                    onToggleRecording = viewModel::toggleRecording
                )
            }
        }

        // TTS 경고 메시지 오버레이: 장애물 감지 시 화면 하단에 3초간 표시
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
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xCC000000)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFFEB3B),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = alertMessage,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Error snackbar
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = viewModel::clearError) {
                        Text("확인")
                    }
                }
            ) {
                Text(error)
            }
        }

        // Loading overlay
        if (uiState.isLoading || uiState.isRerouting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (uiState.isRerouting) "경로 재탐색 중..." else "경로 탐색 중...",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            }
        }

        // Arrival dialog
        if (uiState.hasArrived) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text("도착") },
                text = { Text("목적지에 도착했습니다!") },
                confirmButton = {
                    Button(onClick = viewModel::stopNavigation) {
                        Text("확인")
                    }
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
    onToggleRecording: () -> Unit
) {
    var localQuery by remember { mutableStateOf(searchQuery) }

    LaunchedEffect(searchQuery) {
        localQuery = searchQuery
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // App title + 데이터 수집 버튼
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NavBlind",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            RecordingButton(isRecording = isRecording, onClick = onToggleRecording)
        }

        // Search input — 경로 계산 중에는 비활성화
        OutlinedTextField(
            value = localQuery,
            onValueChange = {
                localQuery = it
                onSearchQueryChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (isLoading) "경로 탐색 중..." else "목적지를 입력하세요") },
            enabled = !isLoading,
            leadingIcon = {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Search, contentDescription = "검색")
                }
            },
            trailingIcon = {
                Row {
                    if (localQuery.isNotEmpty() && !isLoading) {
                        IconButton(onClick = {
                            localQuery = ""
                            onClearSearch()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "지우기")
                        }
                    }
                    IconButton(onClick = onVoiceInput, enabled = !isLoading) {
                        Icon(Icons.Default.Mic, contentDescription = "음성 입력")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchQueryChange(localQuery) }),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Voice input button (large, accessible) — 경로 계산 중에는 비활성화
        Button(
            onClick = onVoiceInput,
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("음성으로 목적지 말하기", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Search results
        if (isSearching) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (searchResults.isNotEmpty()) {
            Text(
                text = "검색 결과",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(searchResults) { result ->
                    SearchResultItem(
                        result = result,
                        onClick = { onDestinationSelected(result) }
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        // 감지 로그 패널 (검색 화면 하단)
        if (detectionLog.isNotEmpty()) {
            DetectionLogPanel(
                log = detectionLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
            )
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                result.address?.let { address ->
                    Text(
                        text = address,
                        fontSize = 14.sp,
                        color = Color.Gray,
                        maxLines = 2
                    )
                }
            }

            result.distanceFormatted?.let { distance ->
                Text(
                    text = distance,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primary)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // TODO: 데모 후 삭제 - GPS/VPS 디버그 정보
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "[DEBUG] 위치 정보",
                    color = Color.Yellow,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "GPS: ${uiState.gpsPosition?.let {
                        "%.6f, %.6f (±%.1fm)".format(it.coordinate.latitude, it.coordinate.longitude, it.accuracy)
                    } ?: "없음"}",
                    color = Color.Cyan,
                    fontSize = 11.sp
                )
                Text(
                    text = "VPS: ${uiState.vpsPosition?.let {
                        "%.6f, %.6f (±%.1fm)".format(it.coordinate.latitude, it.coordinate.longitude, it.accuracy)
                    } ?: "없음"}",
                    color = Color.Green,
                    fontSize = 11.sp
                )
                Text(
                    text = "사용중: ${uiState.currentPosition?.source?.name ?: "없음"}",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }
        }
        // TODO: 데모 후 삭제 끝

        // 장애물 감지 알림 (T073)
        detectionState.mostDangerous?.let { obj ->
            if (obj.dangerLevel >= 0.5f) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (obj.dangerLevel >= 0.8f) {
                            Color(0xFFB71C1C).copy(alpha = 0.9f)
                        } else {
                            Color(0xFFE65100).copy(alpha = 0.85f)
                        }
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = obj.toWarningMessage(),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Destination name
        Text(
            text = uiState.destination?.name ?: "목적지",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp)
        )

        // Remaining distance
        uiState.remainingDistance?.let { distance ->
            Text(
                text = if (distance < 1000) "${distance}m" else String.format("%.1fkm", distance / 1000.0),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 24.dp)
            )
            Text(
                text = "남음",
                fontSize = 18.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Current instruction
        uiState.currentInstruction?.let { instruction ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Direction icon
                    val directionIcon = when (instruction.modifier) {
                        com.navblind.domain.model.TurnModifier.LEFT,
                        com.navblind.domain.model.TurnModifier.SLIGHT_LEFT -> Icons.Default.TurnLeft
                        com.navblind.domain.model.TurnModifier.RIGHT,
                        com.navblind.domain.model.TurnModifier.SLIGHT_RIGHT -> Icons.Default.TurnRight
                        com.navblind.domain.model.TurnModifier.UTURN -> Icons.Default.UTurnLeft
                        else -> Icons.Default.ArrowUpward
                    }

                    Icon(
                        directionIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = instruction.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Repeat button
            FloatingActionButton(
                onClick = onRepeat,
                containerColor = Color.White,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "다시 듣기",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Stop button
            FloatingActionButton(
                onClick = onStop,
                containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(80.dp),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "종료",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // 감지 로그 패널 (내비게이션 화면 하단)
        if (detectionState.detectionLog.isNotEmpty()) {
            DetectionLogPanel(
                log = detectionState.detectionLog,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .padding(bottom = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/**
 * 데이터 수집 토글 버튼.
 * 녹화 중이면 빨간 점이 깜빡이며 "수집 중"을 표시한다.
 * 대기 중이면 회색 원형 아이콘으로 최소한의 공간만 차지한다.
 */
@Composable
private fun RecordingButton(isRecording: Boolean, onClick: () -> Unit) {
    val recordingRed = Color(0xFFE53935)

    if (isRecording) {
        val infiniteTransition = rememberInfiniteTransition(label = "rec_blink")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "rec_dot_alpha"
        )

        OutlinedButton(
            onClick = onClick,
            border = ButtonDefaults.outlinedButtonBorder.copy(
                brush = androidx.compose.ui.graphics.SolidColor(recordingRed)
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(recordingRed, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "수집 중",
                fontSize = 13.sp,
                color = recordingRed
            )
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(40.dp)
                .border(1.dp, Color.LightGray, CircleShape)
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = "데이터 수집 시작",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 실시간 감지 로그 패널.
 * 최신 항목이 상단에 표시되며 스크롤 가능합니다.
 * 각 행: [시각] 클래스명  거리  방향  신뢰도  위험도 바
 */
@Composable
private fun DetectionLogPanel(
    log: List<DetectionLogEntry>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xDD000000))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.List,
                    contentDescription = null,
                    tint = Color(0xFF80CBC4),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "감지 로그  (최근 ${log.size}건)",
                    color = Color(0xFF80CBC4),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Divider(color = Color.White.copy(alpha = 0.15f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(2.dp))

            val listState = rememberLazyListState()
            LazyColumn(state = listState) {
                items(log) { entry ->
                    DetectionLogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun DetectionLogRow(entry: DetectionLogEntry) {
    val dangerColor = when {
        entry.dangerLevel >= 0.8f -> Color(0xFFEF5350)  // 빨강
        entry.dangerLevel >= 0.5f -> Color(0xFFFF9800)  // 주황
        else -> Color(0xFF81C784)                        // 초록
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 시각
        Text(
            text = entry.time,
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.width(56.dp)
        )
        // 클래스명
        Text(
            text = entry.className,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp)
        )
        // 거리
        Text(
            text = entry.distance,
            color = Color(0xFF80DEEA),
            fontSize = 11.sp,
            modifier = Modifier.width(38.dp)
        )
        // 방향
        Text(
            text = entry.direction,
            color = Color(0xFFCE93D8),
            fontSize = 11.sp,
            modifier = Modifier.width(30.dp)
        )
        // 신뢰도
        Text(
            text = "%.0f%%".format(entry.confidence * 100),
            color = Color.Gray,
            fontSize = 10.sp,
            modifier = Modifier.width(32.dp)
        )
        // 위험도 바
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
