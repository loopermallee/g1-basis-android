package io.texne.g1.hub.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.texne.g1.hub.ai.ChatGptRepository
import io.texne.g1.hub.ai.ChatGptRepository.ChatMessageData
import io.texne.g1.hub.ai.ChatPersona
import io.texne.g1.hub.ai.ChatPersonas
import io.texne.g1.hub.ai.HudFormatter
import io.texne.g1.hub.model.Repository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.collections.ArrayDeque

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatGptRepository,
    private val serviceRepository: Repository
) : ViewModel() {

    data class UiMessage(
        val id: Long,
        val role: Role,
        val text: String
    ) {
        enum class Role { USER, ASSISTANT }
    }

    sealed interface HudStatus {
        data object Idle : HudStatus
        data class Displayed(val truncated: Boolean, val pageCount: Int) : HudStatus
        data object DisplayFailed : HudStatus
    }

    data class State(
        val availablePersonas: List<ChatPersona> = ChatPersonas.all,
        val selectedPersona: ChatPersona = ChatPersonas.Ershin,
        val messages: List<UiMessage> = emptyList(),
        val isSending: Boolean = false,
        val apiKeyAvailable: Boolean = false,
        val errorMessage: String? = null,
        val hudStatus: HudStatus = HudStatus.Idle
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val history = ArrayDeque<ChatMessageData>()
    private var nextMessageId = 0L
    private var interactiveHudPages: List<List<String>> = emptyList()
    private var interactiveHudCurrentPageIndex = 0

    init {
        viewModelScope.launch {
            chatRepository.observeApiKey().collectLatest { key ->
                _state.update { state ->
                    state.copy(apiKeyAvailable = !key.isNullOrBlank())
                }
            }
        }
    }

    fun onPersonaSelected(persona: ChatPersona) {
        _state.update { state ->
            state.copy(
                selectedPersona = persona,
                messages = emptyList(),
                hudStatus = HudStatus.Idle,
                errorMessage = null
            )
        }
        history.clear()
        interactiveHudPages = emptyList()
        interactiveHudCurrentPageIndex = 0
    }

    fun sendPrompt(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || _state.value.isSending) {
            return
        }

        val persona = _state.value.selectedPersona
        val userMessage = ChatMessageData(role = "user", content = trimmed)
        val uiMessage = UiMessage(id = nextId(), role = UiMessage.Role.USER, text = trimmed)

        history.addLast(userMessage)
        enforceHistoryLimit()

        _state.update { state ->
            state.copy(
                messages = state.messages + uiMessage,
                isSending = true,
                errorMessage = null,
                hudStatus = HudStatus.Idle
            )
        }

        viewModelScope.launch {
            val result = chatRepository.requestChatCompletion(persona, history.toList())
            result.fold(
                onSuccess = { content ->
                    handleAssistantResponse(content, persona)
                },
                onFailure = { throwable ->
                    if (history.isNotEmpty()) {
                        history.removeLast()
                    }
                    _state.update { state ->
                        state.copy(
                            isSending = false,
                            errorMessage = throwable.message ?: "ChatGPT request failed",
                            hudStatus = HudStatus.Idle
                        )
                    }
                }
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun clearHudStatus() {
        _state.update { it.copy(hudStatus = HudStatus.Idle) }
    }

    private fun handleAssistantResponse(content: String, persona: ChatPersona) {
        val assistantMessage = ChatMessageData(role = "assistant", content = content)
        history.addLast(assistantMessage)
        enforceHistoryLimit()

        val uiMessage = UiMessage(id = nextId(), role = UiMessage.Role.ASSISTANT, text = content)
        val formatted = HudFormatter.format(content)
        val interactivePages = formatted.pages.takeIf { pages ->
            pages.size > 1 && persona.hudHoldMillis == null
        }

        _state.update { state ->
            state.copy(
                messages = state.messages + uiMessage,
                isSending = false,
                hudStatus = HudStatus.Idle
            )
        }

        interactiveHudPages = interactivePages ?: emptyList()
        interactiveHudCurrentPageIndex = 0

        viewModelScope.launch {
            val displayed = serviceRepository.displayCenteredOnConnectedGlasses(
                formatted.pages,
                persona.hudHoldMillis
            )

            _state.update { state ->
                state.copy(
                    hudStatus = if (displayed) {
                        HudStatus.Displayed(
                            truncated = formatted.truncated,
                            pageCount = formatted.pages.size
                        )
                    } else {
                        interactiveHudPages = emptyList()
                        interactiveHudCurrentPageIndex = 0
                        HudStatus.DisplayFailed
                    }
                )
            }
        }
    }

    fun onHudPageRequested(pageIndex: Int) {
        displayInteractiveHudPage(pageIndex)
    }

    fun onHudGestureForward() {
        displayInteractiveHudPage(interactiveHudCurrentPageIndex + 1)
    }

    fun onHudGestureBackward() {
        displayInteractiveHudPage(interactiveHudCurrentPageIndex - 1)
    }

    private fun displayInteractiveHudPage(pageIndex: Int) {
        val pages = interactiveHudPages
        if (pages.isEmpty() || pageIndex !in pages.indices || pageIndex == interactiveHudCurrentPageIndex) {
            return
        }

        viewModelScope.launch {
            val success = serviceRepository.displayCenteredPageOnConnectedGlasses(pages, pageIndex)
            if (success && interactiveHudPages === pages) {
                interactiveHudCurrentPageIndex = pageIndex
            } else if (!success) {
                interactiveHudPages = emptyList()
                interactiveHudCurrentPageIndex = 0
                _state.update { state ->
                    state.copy(hudStatus = HudStatus.DisplayFailed)
                }
            }
        }
    }

    private fun enforceHistoryLimit() {
        while (history.size > HISTORY_LIMIT) {
            history.removeFirst()
        }
    }

    private fun nextId(): Long = nextMessageId++

    companion object {
        private const val HISTORY_LIMIT = 8
    }
}
