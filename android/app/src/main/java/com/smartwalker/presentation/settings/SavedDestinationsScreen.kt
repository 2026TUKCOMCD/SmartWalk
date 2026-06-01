package com.smartwalker.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.smartwalker.data.local.entity.LocalDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedDestinationsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val destinations by viewModel.savedDestinations.collectAsState()
    val view = LocalView.current

    // 화면 진입 안내
    LaunchedEffect(Unit) {
        val msg = if (destinations.isEmpty()) "저장된 목적지가 없습니다."
                  else "저장된 목적지 화면입니다. ${destinations.size}개의 목적지가 있습니다."
        view.announceForAccessibility(msg)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("저장된 목적지") },
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
        if (destinations.isEmpty()) {
            // 빈 상태
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .clearAndSetSemantics { contentDescription = "저장된 목적지가 없습니다." },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Place, contentDescription = null,
                        modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Text("저장된 목적지가 없습니다", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(destinations, key = { it.id }) { dest ->
                    DestinationItem(
                        dest = dest,
                        onDelete = { viewModel.deleteDestination(dest.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationItem(dest: LocalDestination, onDelete: () -> Unit) {
    val itemDescription = buildString {
        append(dest.name)
        dest.address?.let { append(". $it") }
        append(". 이용 횟수 ${dest.useCount}회")
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                // 삭제 버튼을 제외하고 나머지 정보를 하나로 병합
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 장식 아이콘 — 접근성 트리 제외
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clearAndSetSemantics {}
            )
            Spacer(Modifier.width(12.dp))

            Column(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = itemDescription }
            ) {
                Text(dest.name, fontWeight = FontWeight.SemiBold)
                if (dest.address != null)
                    Text(dest.address, fontSize = 13.sp, color = Color.Gray)
                Text("이용 횟수: ${dest.useCount}회", fontSize = 12.sp, color = Color.Gray)
            }

            // 삭제 버튼은 독립된 포커스로 분리
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "${dest.name} 삭제"
                    role = Role.Button
                }
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
            }
        }
    }
}
