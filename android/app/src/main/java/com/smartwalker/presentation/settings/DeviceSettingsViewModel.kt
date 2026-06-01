package com.smartwalker.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartwalker.domain.model.SmartGlasses
import com.smartwalker.domain.repository.DeviceRepository
import com.smartwalker.service.streaming.SmartGlassesConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DeviceSettingsViewModel @Inject constructor(
    private val deviceRepository: DeviceRepository,
    private val connectionService: SmartGlassesConnectionService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    val connectionState = connectionService.connectionState
    val batteryLevel = connectionService.batteryLevel

    init {
        loadDevices()
        observeConnection()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            deviceRepository.list()
                .onSuccess { devices -> _uiState.update { it.copy(devices = devices, isLoading = false) } }
                .onFailure { _uiState.update { it.copy(isLoading = false, error = "기기 목록을 불러오지 못했습니다") } }
        }
    }

    fun connectDevice(deviceId: UUID, ipAddress: String) {
        connectionService.connect(deviceId, ipAddress)
    }

    fun disconnectDevice() {
        connectionService.disconnect()
    }

    fun deleteDevice(deviceId: UUID) {
        viewModelScope.launch {
            deviceRepository.delete(deviceId)
                .onSuccess { loadDevices() }
                .onFailure { _uiState.update { it.copy(error = "기기 삭제에 실패했습니다") } }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    private fun observeConnection() {
        viewModelScope.launch {
            connectionState.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }
}

data class DeviceUiState(
    val isLoading: Boolean = false,
    val devices: List<SmartGlasses> = emptyList(),
    val connectionState: SmartGlassesConnectionService.ConnectionState =
        SmartGlassesConnectionService.ConnectionState.Disconnected,
    val error: String? = null
)
