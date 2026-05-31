package com.smartwalker.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartwalker.data.local.entity.LocalPreference
import com.smartwalker.data.remote.PreferenceDto
import com.smartwalker.data.repository.DestinationRepositoryImpl
import com.smartwalker.data.local.entity.LocalDestination
import com.smartwalker.domain.usecase.UpdatePreferencesUseCase
import com.smartwalker.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val updatePreferencesUseCase: UpdatePreferencesUseCase,
    private val destinationRepository: DestinationRepositoryImpl
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val savedDestinations: StateFlow<List<LocalDestination>> = destinationRepository
        .observeSaved()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            userRepository.getPreferences()
                .onSuccess { pref -> _uiState.update { it.copy(preference = pref) } }
        }
    }

    fun updateSpeechRate(rate: Float) = updatePref { it.copy(speechRate = rate) }
    fun updateVibration(enabled: Boolean) = updatePref { it.copy(vibrationEnabled = enabled) }
    fun updateAvoidStairs(avoid: Boolean) = updatePref { it.copy(avoidStairs = avoid) }
    fun updateAlertDistance(meters: Float) = updatePref { it.copy(alertDistanceMeters = meters) }

    private fun updatePref(transform: (LocalPreference) -> LocalPreference) {
        val current = _uiState.value.preference ?: return
        val updated = transform(current)
        _uiState.update { it.copy(preference = updated) }
        viewModelScope.launch {
            updatePreferencesUseCase(PreferenceDto(
                speechRate = updated.speechRate,
                speechPitch = updated.speechPitch,
                alertDistanceMeters = updated.alertDistanceMeters,
                vibrationEnabled = updated.vibrationEnabled,
                avoidStairs = updated.avoidStairs,
                avoidSteepSlopes = updated.avoidSteepSlopes,
                highContrastMode = updated.highContrastMode,
                language = updated.language
            ))
        }
    }

    fun deleteDestination(id: java.util.UUID) {
        viewModelScope.launch { destinationRepository.delete(id) }
    }
}

data class SettingsUiState(
    val preference: LocalPreference? = null,
    val isLoading: Boolean = false
)
