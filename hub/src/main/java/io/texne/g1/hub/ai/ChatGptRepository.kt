package io.texne.g1.hub.ai

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class ChatGptRepository @Inject constructor(
    private val preferences: ChatPreferences,
    private val httpClient: OkHttpClient
) {
    data class ChatMessageData(
        val role: String,
        val content: String
    )

    fun observeApiKey(): StateFlow<String?> = preferences.observeApiKey()

    fun currentApiKey(): String? = preferences.getApiKey()

    suspend fun updateApiKey(key: String?) = preferences.setApiKey(key)

    suspend fun requestChatCompletion(
        persona: ChatPersona,
        history: List<ChatMessageData>
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = preferences.getApiKey()
            ?: return@withContext Result.failure(IllegalStateException("Missing ChatGPT API key"))

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", persona.systemPrompt)
            })
            history.forEach { message ->
                put(JSONObject().apply {
                    put("role", message.role)
                    put("content", message.content)
                })
            }
        }

        val requestJson = JSONObject().apply {
            put("model", DEFAULT_MODEL)
            put("temperature", persona.temperature)
            put("max_tokens", persona.maxTokens)
            put("messages", messages)
        }

        val requestBody = requestJson
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IllegalStateException("Empty response from ChatGPT"))

                if (!response.isSuccessful) {
                    val errorMessage = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull()

                    return@withContext Result.failure(
                        IllegalStateException(errorMessage ?: "ChatGPT request failed with ${response.code}")
                    )
                }

                val content = runCatching {
                    val json = JSONObject(body)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val first = choices.getJSONObject(0)
                        first.optJSONObject("message")?.optString("content")
                    } else {
                        null
                    }
                }.getOrNull()

                if (content.isNullOrBlank()) {
                    return@withContext Result.failure(IllegalStateException("ChatGPT response did not contain any content"))
                }

                Result.success(content.trim())
            }
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
        private const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
