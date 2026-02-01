package com.navblind.service.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeechRecognitionService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    fun initialize() {
        _isAvailable.value = SpeechRecognizer.isRecognitionAvailable(context)
        if (_isAvailable.value) {
            Log.d(TAG, "Speech recognition is available")
        } else {
            Log.w(TAG, "Speech recognition is not available on this device")
        }
    }

    fun startListening(): Flow<RecognitionResult> = callbackFlow {
        if (!_isAvailable.value) {
            trySend(RecognitionResult.Error("음성 인식을 사용할 수 없습니다"))
            close()
            return@callbackFlow
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Volume level changed
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isListening.value = false
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                _isListening.value = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 오류"
                    SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한이 없습니다"
                    SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 타임아웃"
                    SpeechRecognizer.ERROR_NO_MATCH -> "인식된 결과 없음"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
                    SpeechRecognizer.ERROR_SERVER -> "서버 오류"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 타임아웃"
                    else -> "알 수 없는 오류"
                }
                Log.e(TAG, "Recognition error: $error - $errorMessage")
                trySend(RecognitionResult.Error(errorMessage))
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "Recognition results: $matches")
                    trySend(RecognitionResult.Success(matches[0], matches))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    trySend(RecognitionResult.Partial(matches[0]))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(listener)
        speechRecognizer?.startListening(recognizerIntent)

        awaitClose {
            stopListening()
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
    }

    fun release() {
        stopListening()
    }

    sealed class RecognitionResult {
        data class Success(val text: String, val alternatives: List<String>) : RecognitionResult()
        data class Partial(val text: String) : RecognitionResult()
        data class Error(val message: String) : RecognitionResult()
    }

    companion object {
        private const val TAG = "SpeechRecognitionService"
    }
}
