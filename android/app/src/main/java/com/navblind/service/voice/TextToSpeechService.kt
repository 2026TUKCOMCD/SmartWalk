package com.navblind.service.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextToSpeechService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var pendingMessages = mutableListOf<String>()

    fun initialize() {
        if (tts != null) return

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Korean language not supported")
                    // Fallback to default
                    tts?.setLanguage(Locale.getDefault())
                }

                tts?.setSpeechRate(SPEECH_RATE)
                tts?.setPitch(SPEECH_PITCH)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }

                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        speakNextPending()
                    }

                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        Log.e(TAG, "TTS error for utterance: $utteranceId")
                        speakNextPending()
                    }
                })

                _isReady.value = true
                Log.d(TAG, "TTS initialized successfully")

                // Speak any pending messages
                speakNextPending()
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun speak(message: String, priority: Priority = Priority.NORMAL) {
        if (!_isReady.value) {
            // Queue message for later
            if (priority == Priority.HIGH) {
                pendingMessages.add(0, message)
            } else {
                pendingMessages.add(message)
            }
            return
        }

        val queueMode = if (priority == Priority.HIGH) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        val utteranceId = "utterance_${System.currentTimeMillis()}"
        tts?.speak(message, queueMode, null, utteranceId)

        Log.d(TAG, "Speaking: $message (priority: $priority)")
    }

    fun stop() {
        tts?.stop()
        pendingMessages.clear()
        _isSpeaking.value = false
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        _isReady.value = false
        pendingMessages.clear()
    }

    private fun speakNextPending() {
        if (pendingMessages.isNotEmpty()) {
            val message = pendingMessages.removeAt(0)
            speak(message)
        }
    }

    enum class Priority {
        NORMAL,
        HIGH
    }

    companion object {
        private const val TAG = "TextToSpeechService"
        private const val SPEECH_RATE = 1.0f
        private const val SPEECH_PITCH = 1.0f
    }
}
