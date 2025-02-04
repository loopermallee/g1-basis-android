package io.texne.g1.subtitles.model

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceClient
import io.texne.g1.basis.client.G1ServiceCommon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Repository @Inject constructor(
    @ApplicationContext private val applicationContext: Context
) {
    enum class RecognizerStatus { INITIALIZING, READY, LISTENING, INTERPRETING }

    data class State(
        val glasses: G1ServiceCommon.Glasses? = null,
        val status: RecognizerStatus = RecognizerStatus.INITIALIZING,
        val justHeard: List<String> = listOf(),
    )

    sealed interface Event
    object RecognitionError: Event

    private val writableState = MutableStateFlow<State>(State())
    val state = writableState.asStateFlow()
    private val writableEvents = MutableSharedFlow<Event>()
    val events = writableEvents.asSharedFlow()

    //

    private lateinit var service: G1ServiceClient
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var speechRecognizerIntent: Intent

    fun initializeSpeechRecognizer(activity: Activity) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
            activity.packageName
        )
        speechRecognizer.setRecognitionListener(object: RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                writableState.value = state.value.copy(
                    status = RecognizerStatus.READY
                )
            }

            override fun onBeginningOfSpeech() {
                writableState.value = state.value.copy(
                    status = RecognizerStatus.LISTENING
                )
            }

            override fun onRmsChanged(rmsdB: Float) {
                // empty
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // empty
            }

            override fun onEndOfSpeech() {
                writableState.value = state.value.copy(
                    status = RecognizerStatus.INTERPRETING
                )
            }

            override fun onError(error: Int) {
                writableEvents.tryEmit(RecognitionError)
            }

            override fun onResults(results: Bundle?) {
                val justHeard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList()
                if(justHeard != null) {
                    writableState.value = state.value.copy(
                        justHeard = justHeard
                    )
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // empty
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // empty
            }
        })
    }

    fun destroySpeechRecognizer() {
        speechRecognizer.destroy()
    }

    fun startRecognition() {
        speechRecognizer.startListening(speechRecognizerIntent)
    }

    fun stopRecognition() {
        speechRecognizer.stopListening()
    }

    fun bindService(coroutineScope: CoroutineScope): Boolean {
        service = G1ServiceClient.open(applicationContext) ?: return false
        coroutineScope.launch {
            service.state.collect {
                writableState.value = state.value.copy(
                    glasses = it?.glasses?.filter { it.status == G1ServiceCommon.GlassesStatus.CONNECTED }?.firstOrNull()
                )
            }
        }
        return true
    }

    fun unbindService() =
        service.close()

    suspend fun displayCentered(text: List<String>) =
        service.displayCentered(state.value.glasses!!.id, text, null)

    suspend fun stopDisplaying() =
        service.stopDisplaying(state.value.glasses!!.id)
}