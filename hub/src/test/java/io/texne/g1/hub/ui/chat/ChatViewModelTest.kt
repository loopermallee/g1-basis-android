package io.texne.g1.hub.ui.chat

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.texne.g1.basis.service.protocol.HudGesture
import io.texne.g1.hub.MainDispatcherRule
import io.texne.g1.hub.ai.ChatGptRepository
import io.texne.g1.hub.ai.ChatPersona
import io.texne.g1.hub.model.Repository
import kotlinx.coroutines.flow.MutableSharedFlow
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
    private lateinit var gestureEvents: MutableSharedFlow<HudGesture>

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        every { chatRepository.observeApiKey() } returns apiKeyFlow
        gestureEvents = MutableSharedFlow()
        every { serviceRepository.observeHudGestures() } returns gestureEvents
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
    fun `gesture next page routes to repository`() = runTest {
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

        gestureEvents.emit(HudGesture.NEXT_PAGE)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            serviceRepository.displayCenteredPageOnConnectedGlasses(match { it.size > 1 }, 1)
        }
    }

    @Test
    fun `gesture previous page routes to repository`() = runTest {
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

        gestureEvents.emit(HudGesture.NEXT_PAGE)
        advanceUntilIdle()
        gestureEvents.emit(HudGesture.PREVIOUS_PAGE)
        advanceUntilIdle()

        coVerify { serviceRepository.displayCenteredPageOnConnectedGlasses(match { it.size > 1 }, 0) }
    }

    @Test
    fun `activation gesture sends draft prompt`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)
        viewModel.onPromptChanged("Hello world")
        coEvery { chatRepository.requestChatCompletion(any(), any()) } returns Result.failure(Exception("fail"))

        gestureEvents.emit(HudGesture.ACTIVATE)
        advanceUntilIdle()

        coVerify(exactly = 1) { chatRepository.requestChatCompletion(any(), any()) }
    }

    @Test
    fun `activation gesture ignored when prompt blank`() = runTest {
        val viewModel = ChatViewModel(chatRepository, serviceRepository)

        gestureEvents.emit(HudGesture.ACTIVATE)
        advanceUntilIdle()

        coVerify(exactly = 0) { chatRepository.requestChatCompletion(any(), any()) }
    }
}
