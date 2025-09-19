package io.texne.g1.hub.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.texne.g1.basis.client.G1ServiceCommon
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
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val activationGestureFlow = MutableStateFlow(loadGesture())

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ACTIVATION_GESTURE) {
            activationGestureFlow.value = loadGesture()
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun observeActivationGesture(): StateFlow<G1ServiceCommon.GestureType> =
        activationGestureFlow.asStateFlow()

    fun currentActivationGesture(): G1ServiceCommon.GestureType = activationGestureFlow.value

    suspend fun setActivationGesture(gesture: G1ServiceCommon.GestureType) = withContext(Dispatchers.IO) {
        sharedPreferences.edit(commit = true) {
            putString(KEY_ACTIVATION_GESTURE, gesture.name)
        }
    }

    private fun loadGesture(): G1ServiceCommon.GestureType {
        val stored = sharedPreferences.getString(KEY_ACTIVATION_GESTURE, null)
        return stored?.let { value ->
            runCatching { G1ServiceCommon.GestureType.valueOf(value) }.getOrNull()
        } ?: DEFAULT_GESTURE
    }

    companion object {
        private const val PREF_NAME = "assistant_preferences"
        private const val KEY_ACTIVATION_GESTURE = "assistant_activation_gesture"
        private val DEFAULT_GESTURE = G1ServiceCommon.GestureType.HOLD
    }
}
