package io.texne.g1.hub.ui.chat

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.texne.g1.hub.MainDispatcherRule
import io.texne.g1.hub.ai.ChatGptRepository
import io.texne.g1.hub.ai.ChatGptRepository.ChatCompletionException
import io.texne.g1.hub.ai.ChatPersona
import io.texne.g1.hub.model.Repository
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @MockK(relaxed = true)
    lateinit var chatRepository: ChatGptRepository

    @MockK(relaxed = true)
    lateinit var serviceRepository: Repository

    private val apiKeyFlow = MutableStateFlow<String?>("test-key")

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        every { chatRepository.observeApiKey() } returns apiKeyFlow
    }

    @Test
    fun `hud page requests trigger repository when interactive pages available`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        val persona = ChatPersona(
            id = "todo",
            displayName = "Todo",
            description = "",
            systemPrompt = "",
            hudHoldMillis = null
        )
        viewModel.onPersonaSelected(persona)

        val response = "Item one. Item two. Item three. Item four. Item five. Item six."
        coEvery { chatRepository.requestChatCompletion(persona, any()) } returns Result.success(response)
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true
        coEvery { serviceRepository.displayCenteredPageOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendPrompt("show my todo list")
        advanceUntilIdle()

        viewModel.onHudPageRequested(1)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredPageOnConnectedGlasses(match { it.size > 1 }, 1)
        }
    }

    @Test
    fun `hud page requests ignored when no pages cached`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        viewModel.onHudPageRequested(0)
        advanceUntilIdle()

        coVerify(exactly = 0) { serviceRepository.displayCenteredPageOnConnectedGlasses(any(), any()) }
    }

    @Test
    fun `missing key failure displays warning on hud`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        coEvery { chatRepository.requestChatCompletion(any(), any()) } returns Result.failure(
            ChatCompletionException.MissingApiKey
        )
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendPrompt("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredOnConnectedGlasses(listOf("⚠️ No ChatGPT key"), null)
        }

        assertEquals("⚠️ No ChatGPT key", viewModel.state.value.errorMessage)
    }

    @Test
    fun `invalid key failure displays warning on hud`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        coEvery { chatRepository.requestChatCompletion(any(), any()) } returns Result.failure(
            ChatCompletionException.Http(code = 401, errorMessage = "Invalid key")
        )
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendPrompt("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredOnConnectedGlasses(listOf("⚠️ No ChatGPT key"), null)
        }

        assertEquals("⚠️ No ChatGPT key", viewModel.state.value.errorMessage)
    }

    @Test
    fun `network failure displays warning on hud`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        coEvery { chatRepository.requestChatCompletion(any(), any()) } returns Result.failure(
            ChatCompletionException.Network(IOException("boom"))
        )
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendPrompt("hello")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredOnConnectedGlasses(listOf("⚠️ Connection error"), null)
        }

        assertEquals("⚠️ Connection error", viewModel.state.value.errorMessage)
    }
}
