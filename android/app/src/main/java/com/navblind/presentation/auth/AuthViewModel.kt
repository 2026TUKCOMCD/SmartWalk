package com.navblind.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navblind.service.voice.VoiceGuidedRegistrationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val registrationService: VoiceGuidedRegistrationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        registrationService.announceWelcome()
    }

    fun login(onSuccess: () -> Unit) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        registrationService.performLogin(
            onSuccess = {
                _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                onSuccess()
            },
            onFailure = {
                _uiState.update { it.copy(isLoading = false, error = "로그인에 실패했습니다") }
            }
        )
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)
