package io.texne.g1.hub.ui.chat

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.texne.g1.hub.MainDispatcherRule
import io.texne.g1.hub.ai.ChatGptRepository
import io.texne.g1.hub.ai.ChatPersona
import io.texne.g1.hub.model.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.BeforeTest
import kotlin.test.Test

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
    fun `hud forward gesture triggers repository when interactive pages available`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        val persona = ChatPersona(
            id = "todo",
            displayName = "Todo",
            description = "",
            systemPrompt = "",
            hudHoldMillis = null
        )
        viewModel.onPersonaSelected(persona)

        val response = List(12) { index -> "Task ${index + 1}" }.joinToString(separator = ". ")
        coEvery { chatRepository.requestChatCompletion(persona, any()) } returns Result.success(response)
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true
        coEvery { serviceRepository.displayCenteredPageOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendPrompt("show my todo list")
        advanceUntilIdle()

        viewModel.onHudGestureForward()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredPageOnConnectedGlasses(match { it.size > 1 }, 1)
        }
    }

    @Test
    fun `hud backward gesture displays previous page when available`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        val persona = ChatPersona(
            id = "todo",
            displayName = "Todo",
            description = "",
            systemPrompt = "",
            hudHoldMillis = null
        )
        viewModel.onPersonaSelected(persona)

        val response = List(10) { index -> "Task ${index + 1}" }.joinToString(separator = ". ")
        coEvery { chatRepository.requestChatCompletion(persona, any()) } returns Result.success(response)
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true
        coEvery { serviceRepository.displayCenteredPageOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendPrompt("show my todo list")
        advanceUntilIdle()

        viewModel.onHudGestureForward()
        advanceUntilIdle()

        viewModel.onHudGestureBackward()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredPageOnConnectedGlasses(match { it.size > 1 }, 0)
        }
    }

    @Test
    fun `hud gestures ignored when no pages cached`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        viewModel.onHudGestureForward()
        viewModel.onHudGestureBackward()
        advanceUntilIdle()

        coVerify(exactly = 0) { serviceRepository.displayCenteredPageOnConnectedGlasses(any(), any()) }
    }
}
