package com.navblind.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    viewModel: NavigationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isNavigating -> {
                NavigatingView(
                    uiState = uiState,
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
                    onSearchQueryChange = { query ->
                        if (query.length >= 2) {
                            viewModel.searchDestination(query)
                        }
                    },
                    onVoiceInput = viewModel::startVoiceInput,
                    onDestinationSelected = viewModel::startNavigation,
                    onClearSearch = viewModel::clearSearchResults
                )
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
    onSearchQueryChange: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onDestinationSelected: (SearchResult) -> Unit,
    onClearSearch: () -> Unit
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
        // App title
        Text(
            text = "NavBlind",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Search input
        OutlinedTextField(
            value = localQuery,
            onValueChange = {
                localQuery = it
                onSearchQueryChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("목적지를 입력하세요") },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "검색")
            },
            trailingIcon = {
                Row {
                    if (localQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            localQuery = ""
                            onClearSearch()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "지우기")
                        }
                    }
                    IconButton(onClick = onVoiceInput) {
                        Icon(Icons.Default.Mic, contentDescription = "음성 입력")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchQueryChange(localQuery) }),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Voice input button (large, accessible)
        Button(
            onClick = onVoiceInput,
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

            LazyColumn {
                items(searchResults) { result ->
                    SearchResultItem(
                        result = result,
                        onClick = { onDestinationSelected(result) }
                    )
                }
            }
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

        Spacer(modifier = Modifier.height(32.dp))
    }
}
