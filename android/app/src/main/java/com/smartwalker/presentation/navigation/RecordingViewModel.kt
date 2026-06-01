package com.smartwalker.presentation.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import com.smartwalker.domain.usecase.ToggleDataCollectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RecordingViewModel @Inject constructor(
    private val toggleDataCollectionUseCase: ToggleDataCollectionUseCase
) : ViewModel() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun toggleRecording() {
        if (_isRecording.value) {
            toggleDataCollectionUseCase.stop()
            _isRecording.value = false
            Log.d(TAG, "Data collection stopped")
        } else {
            toggleDataCollectionUseCase.start()
            _isRecording.value = true
            Log.d(TAG, "Data collection started")
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_isRecording.value) {
            toggleDataCollectionUseCase.stop()
        }
    }

    companion object {
        private const val TAG = "RecordingViewModel"
    }
}
