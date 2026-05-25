package com.navblind.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.navblind.domain.model.SmartGlasses
import com.navblind.service.streaming.SmartGlassesConnectionService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicePairingScreen(
    onBack: () -> Unit,
    viewModel: DeviceSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val battery by viewModel.batteryLevel.collectAsState()
    val view = LocalView.current

    // 화면 진입 안내
    LaunchedEffect(Unit) {
        view.announceForAccessibility("스마트글래스 연결 화면입니다.")
    }

    // 연결 상태 변경 시 낭독
    LaunchedEffect(uiState.connectionState) {
        val msg = when (val s = uiState.connectionState) {
            is SmartGlassesConnectionService.ConnectionState.Connected -> "스마트글래스가 연결되었습니다."
            is SmartGlassesConnectionService.ConnectionState.Failed -> "연결 실패: ${s.reason}"
            is SmartGlassesConnectionService.ConnectionState.Reconnecting -> "${s.attempt}번째 재연결 시도 중입니다."
            else -> null
        }
        msg?.let { view.announceForAccessibility(it) }
    }

    // 오류 발생 시 즉시 낭독
    LaunchedEffect(uiState.error) {
        uiState.error?.let { view.announceForAccessibility(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("스마트글래스 연결") },
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
        ) {
            ConnectionStatusCard(uiState.connectionState, battery)
            Spacer(Modifier.height(16.dp))

            if (uiState.isLoading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "기기 목록을 불러오는 중입니다." },
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.clearAndSetSemantics {})
                }
            } else if (uiState.devices.isEmpty()) {
                EmptyDevicesCard()
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.devices) { device ->
                        DeviceCard(
                            device = device,
                            isConnected = uiState.connectionState is
                                    SmartGlassesConnectionService.ConnectionState.Connected,
                            onConnect = { ip -> viewModel.connectDevice(device.id, ip) },
                            onDisconnect = viewModel::disconnectDevice,
                            onDelete = { viewModel.deleteDevice(device.id) }
                        )
                    }
                }
            }

            // 오류 스낵바 — Assertive 로 즉시 낭독
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    action = {
                        TextButton(
                            onClick = viewModel::clearError,
                            modifier = Modifier.semantics { contentDescription = "오류 확인" }
                        ) { Text("확인") }
                    }
                ) { Text(error) }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    state: SmartGlassesConnectionService.ConnectionState,
    battery: Int?
) {
    val (label, color) = when (state) {
        is SmartGlassesConnectionService.ConnectionState.Connected  -> "연결됨"      to Color(0xFF4CAF50)
        is SmartGlassesConnectionService.ConnectionState.Connecting -> "연결 중"     to Color(0xFFFF9800)
        is SmartGlassesConnectionService.ConnectionState.Reconnecting ->
            "재연결 중 (${state.attempt}회)" to Color(0xFFFF9800)
        is SmartGlassesConnectionService.ConnectionState.Failed     -> "연결 실패"   to Color(0xFFF44336)
        else                                                         -> "연결 없음"   to Color.Gray
    }
    val batteryText = battery?.let { ". 배터리 $it 퍼센트" } ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 카드 전체를 하나의 상태 문장으로 읽힘
            .clearAndSetSemantics {
                contentDescription = "스마트글래스 상태: $label$batteryText"
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, color = color)
                if (battery != null) Text("배터리: $battery%", fontSize = 13.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: SmartGlasses,
    isConnected: Boolean,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onDelete: () -> Unit
) {
    var showIpDialog by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf(device.ipAddress ?: "192.168.4.1") }
    val connectionStatus = if (isConnected && device.isConnected) "연결됨" else "연결 안됨"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 장식 아이콘 제외
                Icon(Icons.Default.Glasses, contentDescription = null,
                    modifier = Modifier.clearAndSetSemantics {})
                Spacer(Modifier.width(8.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = buildString {
                                append("기기: ${device.deviceName}")
                                append(". 상태: $connectionStatus")
                                device.ipAddress?.let { append(". IP: $it") }
                            }
                        }
                ) {
                    Text(device.deviceName, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clearAndSetSemantics {})
                    Text(device.deviceId, fontSize = 12.sp, color = Color.Gray,
                        modifier = Modifier.clearAndSetSemantics {})
                    if (device.ipAddress != null)
                        Text("IP: ${device.ipAddress}", fontSize = 12.sp, color = Color.Gray,
                            modifier = Modifier.clearAndSetSemantics {})
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics {
                        contentDescription = "${device.deviceName} 삭제"
                        role = Role.Button
                    }
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (isConnected && device.isConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "${device.deviceName} 연결 해제" },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) { Text("연결 해제") }
            } else {
                Button(
                    onClick = { showIpDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "${device.deviceName} 연결하기. IP 주소를 입력합니다." }
                ) { Text("연결") }
            }
        }
    }

    if (showIpDialog) {
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text("IP 주소 입력") },
            text = {
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    label = { Text("ESP32-CAM IP 주소") },
                    singleLine = true,
                    modifier = Modifier.semantics {
                        contentDescription = "IP 주소 입력란. 현재 값: $ipInput"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = { showIpDialog = false; onConnect(ipInput) },
                    modifier = Modifier.semantics { contentDescription = "입력한 IP 로 연결" }
                ) { Text("연결") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showIpDialog = false },
                    modifier = Modifier.semantics { contentDescription = "취소하고 닫기" }
                ) { Text("취소") }
            }
        )
    }
}

@Composable
private fun EmptyDevicesCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "등록된 스마트글래스가 없습니다." }
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Glasses, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = Color.Gray)
            Spacer(Modifier.height(8.dp))
            Text("등록된 스마트글래스가 없습니다", color = Color.Gray)
        }
    }
}
