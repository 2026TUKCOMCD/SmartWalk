package com.smartwalker.service.voice

import android.util.Log
import com.smartwalker.domain.usecase.LoginUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 음성 안내로 회원가입/로그인 흐름을 안내하는 서비스.
 * 화면을 보지 않고도 TTS 안내만으로 가입 절차를 완료할 수 있도록 지원한다.
 */
@Singleton
class VoiceGuidedRegistrationService @Inject constructor(
    private val tts: TextToSpeechService,
    private val loginUseCase: LoginUseCase
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun announceWelcome() {
        tts.speak(
            "NavBlind에 오신 것을 환영합니다. 화면 중앙을 탭하여 로그인하세요.",
            TextToSpeechService.Priority.HIGH
        )
    }

    fun announceLoginStart() {
        tts.speak("로그인을 시작합니다.", TextToSpeechService.Priority.NORMAL)
    }

    fun announceLoginSuccess(displayName: String?) {
        val name = if (displayName.isNullOrBlank()) "" else "${displayName}님, "
        tts.speak("${name}로그인이 완료되었습니다.", TextToSpeechService.Priority.HIGH)
    }

    fun announceLoginFailure() {
        tts.speak("로그인에 실패했습니다. 다시 시도해주세요.", TextToSpeechService.Priority.HIGH)
    }

    fun performLogin(onSuccess: () -> Unit, onFailure: () -> Unit) {
        announceLoginStart()
        scope.launch {
            loginUseCase()
                .onSuccess { user ->
                    announceLoginSuccess(user.displayName)
                    onSuccess()
                }
                .onFailure { error ->
                    Log.e(TAG, "Login failed", error)
                    announceLoginFailure()
                    onFailure()
                }
        }
    }

    companion object {
        private const val TAG = "VoiceGuidedRegistration"
    }
}
