package com.navblind.service.voice

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 음성 입력을 처리하는 서비스입니다.
 *
 * SpeechRecognitionService를 래핑하여 네비게이션에 특화된 음성 입력 흐름을 제공합니다.
 * - 목적지 입력: TTS 안내 → 대기 → 음성 인식
 * - 명령 입력: 즉시 음성 인식 시작
 * - 에러 시 TTS로 피드백
 */
@Singleton
class VoiceInputService @Inject constructor(
    private val speechRecognitionService: SpeechRecognitionService,
    private val textToSpeechService: TextToSpeechService,
    private val voiceCommandParser: VoiceCommandParser
) {
    val isListening: StateFlow<Boolean> = speechRecognitionService.isListening

    fun initialize() {
        textToSpeechService.initialize()
        speechRecognitionService.initialize()
    }

    /**
     * 목적지 입력을 받습니다.
     * "목적지를 말씀해주세요" 안내 후 TTS 완료를 기다렸다가 음성 인식을 시작합니다.
     */
    fun listenForDestination(): Flow<VoiceInputResult> = flow {
        textToSpeechService.speak("목적지를 말씀해주세요")

        // TTS 시작 대기
        delay(100)
        if (!textToSpeechService.isSpeaking.value) {
            textToSpeechService.isSpeaking.first { it }
        }
        // TTS 완료 대기
        textToSpeechService.isSpeaking.first { !it }

        // 오디오 시스템 안정화 대기
        delay(400)

        Log.d(TAG, "Starting destination recognition")
        speechRecognitionService.startListening().collect { result ->
            when (result) {
                is SpeechRecognitionService.RecognitionResult.Success -> {
                    Log.d(TAG, "Destination recognized: ${result.text}")
                    emit(VoiceInputResult.Recognized(result.text))
                }
                is SpeechRecognitionService.RecognitionResult.Partial -> {
                    emit(VoiceInputResult.Partial(result.text))
                }
                is SpeechRecognitionService.RecognitionResult.Error -> {
                    Log.e(TAG, "Destination recognition error: ${result.message}")
                    textToSpeechService.speak("음성을 인식하지 못했습니다. 다시 말씀해주세요.")
                    emit(VoiceInputResult.Failed(result.message))
                }
            }
        }
    }

    /**
     * 네비게이션 명령(반복, 중지 등)을 받습니다.
     * TTS 안내 없이 즉시 음성 인식을 시작합니다.
     */
    fun listenForCommand(): Flow<VoiceInputResult> = flow {
        Log.d(TAG, "Starting command recognition")
        speechRecognitionService.startListening().collect { result ->
            when (result) {
                is SpeechRecognitionService.RecognitionResult.Success -> {
                    Log.d(TAG, "Command recognized: ${result.text}")
                    emit(VoiceInputResult.Recognized(result.text))
                }
                is SpeechRecognitionService.RecognitionResult.Partial -> {
                    emit(VoiceInputResult.Partial(result.text))
                }
                is SpeechRecognitionService.RecognitionResult.Error -> {
                    Log.e(TAG, "Command recognition error: ${result.message}")
                    emit(VoiceInputResult.Failed(result.message))
                }
            }
        }
    }

    /**
     * 네비게이션 명령을 인식하고 파싱된 NavigationCommand를 반환합니다.
     * TTS 안내 없이 즉시 음성 인식을 시작하며, 인식 결과를 VoiceCommandParser로 해석합니다.
     *
     * 지원 명령: 반복(Repeat), 중지(Stop), 남은거리(RemainingDistance), 현재위치(WhereAmI)
     */
    fun listenForNavigationCommand(): Flow<NavigationCommand> = flow {
        Log.d(TAG, "Starting navigation command recognition")
        speechRecognitionService.startListening().collect { result ->
            when (result) {
                is SpeechRecognitionService.RecognitionResult.Success -> {
                    val command = voiceCommandParser.parse(result.text)
                    Log.d(TAG, "Command parsed: '${result.text}' → $command")
                    emit(command)
                }
                is SpeechRecognitionService.RecognitionResult.Error -> {
                    Log.e(TAG, "Command recognition error: ${result.message}")
                    emit(NavigationCommand.Unknown(""))
                }
                is SpeechRecognitionService.RecognitionResult.Partial -> {
                    // 중간 결과는 명령 파싱에 사용하지 않음
                }
            }
        }
    }

    fun stopListening() {
        speechRecognitionService.stopListening()
        Log.d(TAG, "Stopped listening")
    }

    sealed class VoiceInputResult {
        /** 최종 인식 결과 */
        data class Recognized(val text: String) : VoiceInputResult()
        /** 중간 인식 결과 (UI 실시간 표시용) */
        data class Partial(val text: String) : VoiceInputResult()
        /** 인식 실패 */
        data class Failed(val reason: String) : VoiceInputResult()
    }

    companion object {
        private const val TAG = "VoiceInputService"
    }
}
