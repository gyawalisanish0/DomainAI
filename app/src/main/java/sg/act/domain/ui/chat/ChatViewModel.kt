package sg.act.domain.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import sg.act.domain.data.local.ModelProfileStore
import sg.act.domain.data.model.Conversation
import sg.act.domain.data.repository.ChatRepository
import sg.act.domain.inference.InstalledModel
import sg.act.domain.inference.ModelManager
import sg.act.domain.inference.ModelProfile
import sg.act.domain.privacy.PrivacyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation = Conversation(),
    val conversations: List<Conversation> = emptyList(),
    val activeId: String? = null,
    val privacy: PrivacyState = PrivacyState(),
    val input: String = "",
    val isGenerating: Boolean = false,
    val modelState: ModelManager.State = ModelManager.State.NotLoaded,
    // Download/import in flight (separate from the in-memory load above).
    val transfer: ModelManager.TransferState = ModelManager.TransferState.Idle,
    // Model selection (for the chat-screen picker + subtitle).
    val installed: List<InstalledModel> = emptyList(),
    val savedProfiles: List<ModelProfile> = emptyList(),
    val activeProfileId: String? = null,
    // Inference quick-panel: GPU offload + context length (0 = Auto).
    val gpuEnabled: Boolean = true,
    val contextTokens: Int = 0,
    val contextOptions: List<Int> = emptyList(),
    val effectiveContextTokens: Int = 0,
    val threadCount: Int = 0,
    val threadOptions: List<Int> = emptyList(),
    val effectiveThreads: Int = 0,
)

class ChatViewModel(
    private val repository: ChatRepository,
    private val modelManager: ModelManager,
    private val profileStore: ModelProfileStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private data class Snapshot(
        val conversation: Conversation,
        val conversations: List<Conversation>,
        val activeId: String?,
        val privacy: PrivacyState,
        val modelState: ModelManager.State,
    )

    init {
        viewModelScope.launch { repository.restore() }
        // Seed the inference-panel values (imperative getters, not flows); they're
        // preserved across the combine below, which only overrides the fields it lists.
        _ui.value = _ui.value.copy(
            gpuEnabled = modelManager.gpuEnabled(),
            contextTokens = modelManager.contextTokens(),
            contextOptions = modelManager.contextOptions(),
            effectiveContextTokens = modelManager.effectiveContextTokens(),
            threadCount = modelManager.threadCount(),
            threadOptions = modelManager.threadOptions(),
            effectiveThreads = modelManager.effectiveThreads(),
        )
        val core = combine(
            repository.conversation,
            repository.conversations,
            repository.activeId,
            repository.privacyState,
            modelManager.state,
        ) { convo, list, activeId, privacy, modelState ->
            Snapshot(convo, list, activeId, privacy, modelState)
        }
        combine(
            core,
            modelManager.installed,
            profileStore.profiles,
            profileStore.activeProfileId,
            modelManager.transfer,
        ) { s, installed, profiles, activeProfileId, transfer ->
            _ui.value.copy(
                conversation = s.conversation,
                conversations = s.conversations,
                activeId = s.activeId,
                privacy = s.privacy,
                modelState = s.modelState,
                installed = installed,
                savedProfiles = profiles,
                activeProfileId = activeProfileId,
                transfer = transfer,
            )
        }.onEach { _ui.value = it }.launchIn(viewModelScope)
    }

    /** Pick an on-device model: load it and route chats locally. */
    fun selectLocalModel(fileName: String) {
        modelManager.startSelect(fileName)
        profileStore.setActiveProfileId(null)
        repository.setPreferCloud(false)
    }

    /** Switch to a saved cloud profile: update the active remote config and route to cloud. */
    fun switchToProfile(profile: ModelProfile) = viewModelScope.launch {
        repository.saveRemoteConfig(profile.toRemoteConfig())
        profileStore.setActiveProfileId(profile.id)
        repository.setPreferCloud(true)
    }

    /** Toggle GPU offload from the chat quick-panel; reloads the active model. */
    fun setGpuEnabled(enabled: Boolean) {
        modelManager.setGpuEnabled(enabled)
        _ui.value = _ui.value.copy(gpuEnabled = enabled)
    }

    /** Set the context length (0 = Auto) from the chat quick-panel; reloads the model. */
    fun setContextTokens(tokens: Int) {
        modelManager.setContextTokens(tokens)
        _ui.value = _ui.value.copy(
            contextTokens = tokens,
            effectiveContextTokens = modelManager.effectiveContextTokens(),
        )
    }

    /** Set the generation thread count (0 = Auto) from the quick-panel; reloads the model. */
    fun setThreadCount(count: Int) {
        modelManager.setThreadCount(count)
        _ui.value = _ui.value.copy(
            threadCount = count,
            effectiveThreads = modelManager.effectiveThreads(),
        )
    }

    fun updateInput(text: String) {
        _ui.value = _ui.value.copy(input = text)
    }

    /**
     * Triggered by the send button. The picked model decides routing: a cloud model
     * routes to the cloud (subject to consent + the kill switch), a local model
     * stays on-device. Redaction (when enabled) is applied silently in the router;
     * what was actually sent is shown beneath the reply.
     */
    fun onSend() {
        val text = _ui.value.input.trim()
        if (text.isEmpty() || _ui.value.isGenerating) return

        // Route to the cloud only when a cloud model is the picked model. When it's
        // picked but currently blocked (kill switch / no consent), still request
        // cloud so the router answers locally *with a note* explaining why.
        val requestCloud = _ui.value.activeProfileId != null
        dispatch(text, useCloud = requestCloud)
    }

    private var generationJob: Job? = null

    private fun dispatch(text: String, useCloud: Boolean) {
        _ui.value = _ui.value.copy(input = "", isGenerating = true)
        generationJob = viewModelScope.launch {
            try {
                repository.send(text, useCloud)
            } catch (_: CancellationException) {
                // User stopped generation; partial reply was already persisted.
            } finally {
                _ui.value = _ui.value.copy(isGenerating = false)
            }
        }
    }

    /** Cancel the in-progress generation, keeping whatever was produced so far. */
    fun stopGeneration() {
        generationJob?.cancel()
    }

    fun newConversation() {
        repository.newConversation()
    }

    fun selectConversation(id: String) {
        repository.selectConversation(id)
    }

    fun deleteConversation(id: String) = viewModelScope.launch {
        repository.deleteConversation(id)
    }

    fun renameConversation(id: String, title: String) = viewModelScope.launch {
        repository.renameConversation(id, title)
    }

    class Factory(
        private val repository: ChatRepository,
        private val modelManager: ModelManager,
        private val profileStore: ModelProfileStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(repository, modelManager, profileStore) as T
    }
}
