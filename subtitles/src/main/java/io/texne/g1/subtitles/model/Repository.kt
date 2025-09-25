package io.texne.g1.subtitles.model

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceClient
import io.texne.g1.basis.client.G1ServiceCommon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val recognizer: Recognizer
) {
    data class State(
        val hubInstalled: Boolean = true,
        val glasses: G1ServiceCommon.Glasses? = null,
        val started: Boolean = false,
        val listening: Boolean = false,
        val errorMessage: String? = null
    )

    sealed interface Event {
        object RecognitionError : Event
        data class SpeechRecognized(val text: List<String>) : Event
    }

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()
    private val writableEvents = MutableSharedFlow<Event>()
    val events = writableEvents.asSharedFlow()

    init {
        val hubInstalled = try {
            applicationContext.packageManager.getPackageInfo("io.texne.g1.hub", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        writableState.value = State(
            hubInstalled = hubInstalled,
            errorMessage = if (hubInstalled) {
                null
            } else {
                "Basis Hub is not installed. Install Basis Hub, then reopen the Subtitles app."
            }
        )
    }

    //

    private lateinit var viewModelScope: CoroutineScope
    private var recognizerStateJob: Job? = null
    private var recognizerEventJob: Job? = null

    //

    private var service: G1ServiceClient? = null

    fun initializeSpeechRecognizer(coroutineScope: CoroutineScope) {
        recognizer.create(coroutineScope)
        recognizerEventJob = coroutineScope.launch {
            recognizer.events.collect {
                writableEvents.emit(when {
                    it == Recognizer.Event.Error -> Event.RecognitionError
                    it is Recognizer.Event.Heard -> Event.SpeechRecognized(it.text)
                    else -> Event.RecognitionError
                })
            }
        }
        recognizerStateJob = coroutineScope.launch {
            recognizer.state.collect {
                writableState.value = state.value.copy(
                    started = it.started,
                    listening = it.listening
                )
            }
        }
    }

    fun destroySpeechRecognizer() {
        recognizerStateJob?.cancel()
        recognizerStateJob = null
        recognizerEventJob?.cancel()
        recognizerEventJob = null
        recognizer.destroy()
    }

    fun startRecognition(coroutineScope: CoroutineScope) {
        viewModelScope = coroutineScope
        viewModelScope.launch {
            recognizer.start()
        }
    }

    fun stopRecognition() {
        viewModelScope.launch {
            stopDisplaying()
        }
        recognizer.stop()
    }

    fun bindService(coroutineScope: CoroutineScope): Boolean {
        val client = G1ServiceClient.open(applicationContext) ?: run {
            writableState.value = state.value.copy(
                errorMessage = "Unable to connect to Basis Hub service. Open Basis Hub and try again. (Error: service unavailable)"
            )
            return false
        }
        service = client
        writableState.value = state.value.copy(errorMessage = null)
        coroutineScope.launch {
            client.state.collect {
                val connectedGlasses = it?.glasses?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }?.firstOrNull()
                writableState.value = state.value.copy(
                    glasses = connectedGlasses,
                    errorMessage = if (it == null) {
                        "Lost connection to Basis Hub service. Reopen Basis Hub and relaunch the Subtitles app."
                    } else {
                        null
                    }
                )
            }
        }
        return true
    }

    fun unbindService() {
        service?.close()
        service = null
    }

    suspend fun displayText(text: List<String>) {
        val glasses = state.value.glasses ?: return
        val client = service ?: return
        client.displayFormattedPage(glasses.id, G1ServiceCommon.FormattedPage(
            lines = text.map { G1ServiceCommon.FormattedLine(text = it, justify = G1ServiceCommon.JustifyLine.LEFT) },
            justify = G1ServiceCommon.JustifyPage.BOTTOM
        ))
    }

    suspend fun stopDisplaying() {
        val glasses = state.value.glasses ?: return
        val client = service ?: return
        client.stopDisplaying(glasses.id)
    }
}