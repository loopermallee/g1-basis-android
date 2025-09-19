package io.texne.g1.hub.assistant

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class AssistantPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val activationGestureFlow = MutableStateFlow(readGesture())

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ACTIVATION_GESTURE) {
            activationGestureFlow.value = readGesture()
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun observeActivationGesture(): StateFlow<AssistantActivationGesture> =
        activationGestureFlow.asStateFlow()

    fun getActivationGesture(): AssistantActivationGesture = readGesture()

    suspend fun setActivationGesture(gesture: AssistantActivationGesture) =
        withContext(Dispatchers.IO) {
            sharedPreferences.edit(commit = true) {
                if (gesture == AssistantActivationGesture.OFF) {
                    remove(KEY_ACTIVATION_GESTURE)
                } else {
                    putString(KEY_ACTIVATION_GESTURE, gesture.name)
                }
            }
        }

    private fun readGesture(): AssistantActivationGesture {
        val stored = sharedPreferences.getString(KEY_ACTIVATION_GESTURE, null)
        return stored?.let { runCatching { AssistantActivationGesture.valueOf(it) }.getOrNull() }
            ?: AssistantActivationGesture.OFF
    }

    private companion object {
        private const val PREF_NAME = "assistant_preferences"
        private const val KEY_ACTIVATION_GESTURE = "activation_gesture"
    }
}
