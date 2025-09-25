package io.texne.g1.hub.ui.chat

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun `refreshConnection triggers repository scan`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        viewModel.refreshConnection()

        verify(exactly = 1) { serviceRepository.startLooking() }
        val diagnostic = viewModel.state.value.diagnosticMessage
        assertNotNull(diagnostic)
        assertFalse(diagnostic.isError)
        assertEquals("Refreshing glasses connection…", diagnostic.text)
    }

    @Test
    fun `sendHudTestMessage emits success diagnostic`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns true

        viewModel.sendHudTestMessage()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredOnConnectedGlasses(
                match { pages ->
                    pages.size == 1 && pages.first().contains("Connection test")
                },
                any()
            )
        }
        val diagnostic = viewModel.state.value.diagnosticMessage
        assertNotNull(diagnostic)
        assertFalse(diagnostic.isError)
        assertTrue(diagnostic.text.contains("test message", ignoreCase = true))
    }

    @Test
    fun `sendHudTestMessage emits failure diagnostic when display fails`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        coEvery { serviceRepository.displayCenteredOnConnectedGlasses(any(), any()) } returns false

        viewModel.sendHudTestMessage()
        advanceUntilIdle()

        val diagnostic = viewModel.state.value.diagnosticMessage
        assertNotNull(diagnostic)
        assertTrue(diagnostic.isError)
        assertTrue(diagnostic.text.contains("couldn't", ignoreCase = true))
        assertEquals(ChatViewModel.HudStatus.DisplayFailed, viewModel.state.value.hudStatus)
    }

    @Test
    fun `testChatGpt emits success diagnostic`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        coEvery { chatRepository.requestChatCompletion(any(), any()) } returns Result.success("PONG")

        viewModel.testChatGpt()
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.requestChatCompletion(any(), any()) }
        val diagnostic = viewModel.state.value.diagnosticMessage
        assertNotNull(diagnostic)
        assertFalse(diagnostic.isError)
        assertTrue(diagnostic.text.contains("PONG", ignoreCase = true))
        assertFalse(viewModel.state.value.isChatGptTestInProgress)
    }

    @Test
    fun `testChatGpt emits error diagnostic when request fails`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        coEvery { chatRepository.requestChatCompletion(any(), any()) } returns Result.failure(Exception("boom"))

        viewModel.testChatGpt()
        advanceUntilIdle()

        val diagnostic = viewModel.state.value.diagnosticMessage
        assertNotNull(diagnostic)
        assertTrue(diagnostic.isError)
        assertTrue(diagnostic.text.contains("boom"))
    }
}
