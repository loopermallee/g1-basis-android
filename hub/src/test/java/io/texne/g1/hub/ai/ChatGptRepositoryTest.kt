package io.texne.g1.hub.ai

import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class ChatGptRepositoryTest {

    @MockK
    lateinit var preferences: ChatPreferences

    @MockK
    lateinit var httpClient: OkHttpClient

    private lateinit var repository: ChatGptRepository

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        repository = ChatGptRepository(preferences, httpClient)
        every { preferences.getApiKey() } returns "test-key"
    }

    @Test
    fun `network error retried once then succeeds`() = runTest {
        val failingCall = mockk<Call>(relaxed = true)
        val succeedingCall = mockk<Call>(relaxed = true)

        every { httpClient.newCall(any()) } returnsMany listOf(failingCall, succeedingCall)
        every { failingCall.execute() } throws IOException("boom")
        every { succeedingCall.execute() } returns successResponse("Hello world")

        val persona = ChatPersona(
            id = "test",
            displayName = "Test",
            description = "",
            systemPrompt = ""
        )
        val result = repository.requestChatCompletion(
            persona,
            history = listOf(ChatGptRepository.ChatMessageData("user", "hi"))
        )

        assertTrue(result.isSuccess)
        assertEquals("Hello world", result.getOrNull())
        verify(exactly = 2) { httpClient.newCall(any()) }
    }

    @Test
    fun `network error retried once then fails`() = runTest {
        val firstCall = mockk<Call>(relaxed = true)
        val secondCall = mockk<Call>(relaxed = true)

        every { httpClient.newCall(any()) } returnsMany listOf(firstCall, secondCall)
        every { firstCall.execute() } throws IOException("first")
        every { secondCall.execute() } throws IOException("second")

        val persona = ChatPersona(
            id = "test",
            displayName = "Test",
            description = "",
            systemPrompt = ""
        )

        val result = repository.requestChatCompletion(
            persona,
            history = listOf(ChatGptRepository.ChatMessageData("user", "hi"))
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChatGptRepository.ChatCompletionException.Network)
        verify(exactly = 2) { httpClient.newCall(any()) }
    }

    private fun successResponse(content: String): Response {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .build()
        val body = """{"choices":[{"message":{"content":"$content"}}]}"""
            .toResponseBody("application/json".toMediaType())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }
}
