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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.LazyThreadSafetyMode

@Singleton
class AssistantPreferences @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sharedPreferences: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val activationGestureFlow = MutableStateFlow(DEFAULT_GESTURE)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_ACTIVATION_GESTURE) {
            activationGestureFlow.value = loadGesture(prefs)
        }
    }

    init {
        scope.launch {
            val prefs = sharedPreferences
            activationGestureFlow.value = loadGesture(prefs)
            withContext(Dispatchers.Main) {
                prefs.registerOnSharedPreferenceChangeListener(listener)
            }
        }
    }

    fun observeActivationGesture(): StateFlow<G1ServiceCommon.GestureType> =
        activationGestureFlow.asStateFlow()

    fun currentActivationGesture(): G1ServiceCommon.GestureType = activationGestureFlow.value

    suspend fun setActivationGesture(gesture: G1ServiceCommon.GestureType) {
        withContext(Dispatchers.IO) {
            sharedPreferences.edit(commit = true) {
                putString(KEY_ACTIVATION_GESTURE, gesture.name)
            }
        }
        activationGestureFlow.value = gesture
    }

    private fun loadGesture(prefs: SharedPreferences): G1ServiceCommon.GestureType {
        val stored = prefs.getString(KEY_ACTIVATION_GESTURE, null)
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
