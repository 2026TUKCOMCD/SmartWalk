package com.smartwalker.presentation.auth

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.smartwalker.domain.usecase.LoginUseCase
import com.smartwalker.service.auth.FirebaseAuthService
import com.smartwalker.service.voice.VoiceGuidedRegistrationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val registrationService: VoiceGuidedRegistrationService,
    private val authService: FirebaseAuthService,
    private val loginUseCase: LoginUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        if (authService.isSignedIn()) {
            _uiState.update { it.copy(isLoggedIn = true) }
        } else {
            registrationService.announceWelcome()
        }
    }

    fun onPhoneNumberChanged(phone: String) {
        _uiState.update { it.copy(phoneNumber = phone, error = null) }
    }

    fun startPhoneVerification(activity: Activity) {
        val phone = _uiState.value.phoneNumber.filter { it.isDigit() }
        if (phone.length < 10) {
            _uiState.update { it.copy(error = "올바른 전화번호를 입력해주세요") }
            return
        }

        val formatted = FirebaseAuthService.formatKoreanPhoneNumber(phone)
        _uiState.update { it.copy(isLoading = true, error = null) }
        registrationService.announceLoginStart()

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            // SMS 자동 감지 성공 — 사용자가 코드를 입력하지 않아도 됨
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                Log.d(TAG, "자동 인증 완료")
                registrationService.announceAutoVerified()
                signInWithCredential(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(TAG, "인증 실패: ${e.javaClass.simpleName} - ${e.message}", e)
                val msg = when (e) {
                    is FirebaseAuthInvalidCredentialsException -> "전화번호 형식이 올바르지 않습니다"
                    is FirebaseTooManyRequestsException -> "잠시 후 다시 시도해주세요"
                    else -> "[${e.javaClass.simpleName}] ${e.message ?: "인증 요청에 실패했습니다"}"
                }
                _uiState.update { it.copy(isLoading = false, error = msg) }
                registrationService.announceLoginFailure()
            }

            // 자동 감지 실패 시 — 수동 코드 입력 화면으로 전환
            override fun onCodeSent(
                verificationId: String,
                resendToken: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.d(TAG, "인증번호 발송됨")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        step = AuthStep.CODE_INPUT,
                        verificationId = verificationId,
                        resendToken = resendToken
                    )
                }
                registrationService.announceCodeSent()
            }
        }

        authService.startPhoneVerification(formatted, activity, callbacks)
    }

    fun onCodeChanged(code: String) {
        if (code.length > 6) return
        _uiState.update { it.copy(smsCode = code, error = null) }
        if (code.length == 6) submitCode()
    }

    fun submitCode() {
        val state = _uiState.value
        val verificationId = state.verificationId ?: return
        if (state.smsCode.length != 6) {
            _uiState.update { it.copy(error = "6자리 인증번호를 입력해주세요") }
            return
        }
        val credential = PhoneAuthProvider.getCredential(verificationId, state.smsCode)
        signInWithCredential(credential)
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            authService.signInWithPhoneCredential(credential)
                .onSuccess {
                    // 백엔드에 사용자 동기화 (실패해도 Firebase 인증은 유효)
                    loginUseCase(_uiState.value.phoneNumber)
                        .onFailure { e -> Log.w(TAG, "백엔드 동기화 실패 (무시)", e) }
                    registrationService.announceLoginSuccess(null)
                    _uiState.update { it.copy(isLoading = false, isLoggedIn = true) }
                }
                .onFailure { e ->
                    Log.e(TAG, "로그인 실패", e)
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "로그인에 실패했습니다")
                    }
                    registrationService.announceLoginFailure()
                }
        }
    }

    fun goBackToPhoneInput() {
        _uiState.update {
            it.copy(step = AuthStep.PHONE_INPUT, smsCode = "", verificationId = null, error = null)
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    companion object {
        private const val TAG = "AuthViewModel"
    }
}

enum class AuthStep { PHONE_INPUT, CODE_INPUT }

data class AuthUiState(
    val step: AuthStep = AuthStep.PHONE_INPUT,
    val phoneNumber: String = "",
    val smsCode: String = "",
    val verificationId: String? = null,
    val resendToken: PhoneAuthProvider.ForceResendingToken? = null,
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null
)
