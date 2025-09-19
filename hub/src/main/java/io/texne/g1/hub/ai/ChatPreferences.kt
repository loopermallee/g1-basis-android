package io.texne.g1.hub.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class ChatPreferences @Inject constructor(
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

    private val apiKeyFlow = MutableStateFlow(sharedPreferences.getString(KEY_API, null))

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_API) {
            apiKeyFlow.value = sharedPreferences.getString(KEY_API, null)
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun observeApiKey(): StateFlow<String?> = apiKeyFlow.asStateFlow()

    fun getApiKey(): String? = sharedPreferences.getString(KEY_API, null)

    suspend fun setApiKey(key: String?) = withContext(Dispatchers.IO) {
        sharedPreferences.edit(commit = true) {
            if (key.isNullOrBlank()) {
                remove(KEY_API)
            } else {
                putString(KEY_API, key.trim())
            }
        }
    }

    companion object {
        private const val PREF_NAME = "chat_gpt"
        private const val KEY_API = "openai_api_key"
    }
}
