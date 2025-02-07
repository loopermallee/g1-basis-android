package io.texne.g1.subtitles.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.basis.client.G1ServiceCommon
import io.texne.g1.subtitles.model.Repository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubtitlesViewModel @Inject constructor(
    private val repository: Repository
): ViewModel() {

    data class State(
        val glasses: G1ServiceCommon.Glasses? = null,
        val hubInstalled: Boolean = false,
        val started: Boolean = false,
        val listening: Boolean = false,
        val displayText: List<String> = listOf(),
        val queuedText: List<String> = listOf()
    )

    private val writableState = MutableStateFlow(State())
    val state = writableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.state.collect {
                writableState.value = state.value.copy(
                    glasses = it.glasses,
                    hubInstalled = it.hubInstalled,
                    started = it.started,
                    listening = it.listening
                )
            }
        }
        viewModelScope.launch {
            repository.events.collect {
                when (it) {
                    Repository.Event.RecognitionError -> {}
                    is Repository.Event.SpeechRecognized -> {
                        val cutLines = textLinesToGlassesLines(it.text)
                        val linesLeft = mutableListOf<String>()
                        if(state.value.displayText.isEmpty()) {
                            if(cutLines.size > 5) {
                                val firstLines = cutLines.take(5)
                                linesLeft.addAll(cutLines.takeLast(cutLines.size - 5))
                                displayNow(firstLines)
                            } else {
                                displayNow(cutLines)
                                queueJob?.cancel()
                                queueJob = null
                            }
                        }
                        if(linesLeft.isNotEmpty()) {
                            displayQueue.addAll(linesLeft.chunked(5))
                        }
                        if(queueJob == null) {
                            viewModelScope.launch {
                                var somethingDisplayed = false
                                do {
                                    delay(5000)
                                    if(displayQueue.isNotEmpty()) {
                                        val nextOne = displayQueue.removeAt(0)
                                        displayNow(nextOne)
                                        somethingDisplayed = true
                                    } else {
                                        somethingDisplayed = false
                                    }
                                } while (somethingDisplayed)
                                displayNow(listOf())
                                repository.stopDisplaying()
                                queueJob = null
                            }
                        }
                    }
                }
            }
        }
    }

    fun startRecognition() = repository.startRecognition(viewModelScope)
    fun stopRecognition() = repository.stopRecognition()

    //

    private fun displayNow(lines: List<String>) {
        viewModelScope.launch {
            writableState.value = state.value.copy(
                displayText = lines
            )
            repository.displayCentered(lines)
        }
    }

    private val displayQueue: MutableList<List<String>> = mutableListOf()
    private var queueJob: Job? = null

    private fun textLinesToGlassesLines(lines: List<String>): List<String> {
        val glassesLines = mutableListOf<String>()
        lines.forEach { line ->
            if(line.length <= 40) {
                glassesLines.add(line)
            } else {
                var accumulatedLine: String = ""
                line.split(" ").filterNot { it.trim().isEmpty() }.forEach {
                    if((accumulatedLine.length + 1 + it.length) > 40) {
                        glassesLines.add(accumulatedLine)
                        accumulatedLine = it
                    } else {
                        accumulatedLine = "$accumulatedLine $it"
                    }
                }
                if(accumulatedLine.isNotBlank()) {
                    glassesLines.add(line)
                }
            }
        }
        return glassesLines
    }
}