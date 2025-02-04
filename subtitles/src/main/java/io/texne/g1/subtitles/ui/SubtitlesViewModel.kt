package io.texne.g1.subtitles.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.subtitles.model.Repository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubtitlesViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    val state = repository.state
    val events = repository.events

    fun startRecognition() = repository.startRecognition()
    fun stopRecognition() = repository.stopRecognition()
    fun displayText(text: List<String>) {
        viewModelScope.launch {
            repository.displayCentered(text)
        }
    }
    fun stopDisplaying() {
        viewModelScope.launch {
            repository.stopDisplaying()
        }
    }
}