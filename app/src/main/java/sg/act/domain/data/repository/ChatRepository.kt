package sg.act.domain.data.repository

import android.content.Context
import sg.act.domain.R
import sg.act.domain.data.local.ConversationStore
import sg.act.domain.data.local.RemoteConfigStore
import sg.act.domain.data.local.SelectionStore
import sg.act.domain.data.model.Conversation
import sg.act.domain.data.model.Message
import sg.act.domain.data.model.Role
import sg.act.domain.data.model.Route
import sg.act.domain.inference.InferenceEngine
import sg.act.domain.inference.LocalEngine
import sg.act.domain.inference.PrivacyRouter
import sg.act.domain.inference.RemoteEngine
import sg.act.domain.inference.complete
import sg.act.domain.privacy.PiiRedactor
import sg.act.domain.privacy.PrivacySettings
import sg.act.domain.privacy.PrivacyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for chat state. Coordinates the privacy router, engines
 * and encrypted persistence. Owns the full set of conversations (chat history),
 * exposing the active one plus the ordered list for the history drawer.
 */
class ChatRepository(
    private val context: Context,
    val privacySettings: PrivacySettings,
    private val conversationStore: ConversationStore,
    private val remoteConfigStore: RemoteConfigStore,
    private val selectionStore: SelectionStore,
    private val localEngine: InferenceEngine = LocalEngine(),
    /** Current context length, used to budget how much history fits. Read per send. */
    private val contextTokens: () -> Int = { 4096 },
    /** Whether a real on-device model is loaded (summarization needs one). */
    private val localModelLoaded: () -> Boolean = { false },
) {

    private val router = PrivacyRouter(
        local = localEngine,
        remoteProvider = {
            remoteConfigStore.load()?.let { config ->
                PrivacyRouter.Remote(RemoteEngine(config), config.logsData)
            }
        },
    )

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _activeId = MutableStateFlow<String?>(null)

    /** All conversations, most-recently-updated first. */
    val conversations: Flow<List<Conversation>> =
        combine(_conversations, _activeId) { list, _ -> list.sortedByDescending { it.updatedAt } }

    /** The currently open conversation. */
    val conversation: Flow<Conversation> =
        combine(_conversations, _activeId) { list, id ->
            list.firstOrNull { it.id == id } ?: Conversation()
        }

    val activeId: Flow<String?> = _activeId.asStateFlow()

    val privacyState: Flow<PrivacyState> = privacySettings.state

    /** Whether the user's picked chat model is the cloud provider (vs on-device). */
    private val _preferCloud = MutableStateFlow(selectionStore.preferCloud())
    val preferCloud: StateFlow<Boolean> = _preferCloud.asStateFlow()

    /** The configured cloud model id, or null when no provider is set. */
    private val _cloudModelId = MutableStateFlow(remoteConfigStore.load()?.model)
    val cloudModelId: StateFlow<String?> = _cloudModelId.asStateFlow()

    /** Record the local-vs-cloud chat model choice (persisted across launches). */
    fun setPreferCloud(value: Boolean) {
        selectionStore.setPreferCloud(value)
        _preferCloud.value = value
    }

    /** True once history has been loaded from disk; persist is a no-op until then. */
    @Volatile
    private var restored = false

    suspend fun restore() {
        if (restored) return
        // Empty drafts are never worth keeping; drop any that were persisted before
        // and only restore real conversations (clears piles of stray "New chat"s).
        val stored = conversationStore.load()
            .filter { it.messages.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
        if (stored.isEmpty()) {
            val fresh = Conversation()
            _conversations.value = listOf(fresh)
            _activeId.value = fresh.id
        } else {
            _conversations.value = stored
            _activeId.value = stored.first().id
        }
        // Only now is it safe to write back: persisting before the on-disk history
        // is loaded would overwrite it with a partial/empty in-memory list.
        restored = true
    }

    fun previewRedaction(text: String): PiiRedactor.Result = PiiRedactor.redact(text)

    fun hasCloudProvider(): Boolean = remoteConfigStore.load() != null

    /** The model id of the configured cloud provider, or null if none is set. */
    fun activeCloudModelId(): String? = remoteConfigStore.load()?.model

    /**
     * Validate a provider by performing a minimal round-trip, confirming the API
     * key AND that the chosen model id actually routes — before anything is saved.
     */
    suspend fun validateProvider(config: RemoteEngine.Config): Result<Unit> = try {
        RemoteEngine(config).generate("Hi", emptyList()).first()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Append the user's message, route it, then stream Domain AI's reply into a
     * single message that grows token-by-token. The UI re-renders live because it
     * observes [conversation]. Persists once the stream completes.
     */
    suspend fun send(prompt: String, useCloudForThisTurn: Boolean) {
        if (currentActive() == null) newConversation()
        val active = currentActive() ?: return

        // Show the user bubble immediately so the screen isn't blank while history
        // prep (and optional summarization) runs in the background.
        val userMessage = Message(role = Role.USER, text = prompt)
        updateActive { it.addMessage(userMessage).retitleIfNeeded(prompt) }

        // Fit the conversation to the context window (summarize locally / truncate).
        // Uses `active` — the pre-user-message snapshot — so it's safe to add the
        // user bubble to the UI first.
        val history = prepareHistory(active)

        val privacy = privacyState.first()
        val outcome = router.answer(prompt, history, privacy, useCloudForThisTurn)

        // Seed an empty reply carrying the route/preview; the typing indicator inside
        // AssistantContent shows while the first token is on its way.
        updateActive {
            it.addMessage(
                Message(
                    role = Role.DOMAIN,
                    text = "",
                    route = outcome.route,
                    sentPayloadPreview = outcome.sentPayloadPreview,
                ),
            )
        }

        val builder = StringBuilder()
        var errorNote: String? = null
        // On-device generation is CPU-heavy and can outlast a glance away from the
        // app, so run it under a foreground service: the process keeps generating in
        // the background instead of being killed mid-reply. Cloud turns are
        // network-bound and quick, so they don't need it.
        val foreground = outcome.route == Route.LOCAL
        if (foreground) {
            sg.act.domain.core.ForegroundWork.begin(
                context,
                context.getString(R.string.notif_generating),
            )
        }
        try {
            outcome.tokens.smoothStream().collect { fragment ->
                builder.append(fragment)
                updateActive { it.updateLastText(stripLeadingNameLabel(builder.toString())) }
            }
        } catch (e: CancellationException) {
            // User pressed Stop. Keep the partial reply and persist it before the
            // coroutine unwinds, then propagate the cancellation.
            finalizeReply(
                stripLeadingNameLabel(builder.toString()),
                outcome.note ?: context.getString(R.string.reply_stopped),
            )
            throw e
        } catch (e: Exception) {
            errorNote = context.getString(R.string.reply_request_failed, e.message ?: "")
            sg.act.domain.core.CrashReporting.record(e)
        } finally {
            if (foreground) sg.act.domain.core.ForegroundWork.end(context)
        }

        finalizeReply(stripLeadingNameLabel(builder.toString()), outcome.note ?: errorNote)
    }

    /**
     * Strip a leading speaker-label the model sometimes emits despite the system
     * prompt (e.g. "Domain AI:" / "Domain:" at the very start of a reply).
     */
    private fun stripLeadingNameLabel(text: String): String =
        text.replaceFirst(
            Regex("^\\s*(?:Domain AI|Domain|Oracle)\\s*:\\s*", RegexOption.IGNORE_CASE),
            "",
        )

    /** Write the reply's final text (body plus any note) and persist, uncancellably. */
    private suspend fun finalizeReply(body: String, note: String?) {
        val finalText = when {
            note == null -> body
            body.isBlank() -> "_${note}_"
            else -> "$body\n\n_${note}_"
        }
        withContext(NonCancellable) {
            updateActive { it.updateLastText(finalText) }
            persist()
        }
    }

    /**
     * Open a blank chat. If the current chat is already empty we stay on it; any
     * other abandoned empty drafts are dropped, so repeated taps never pile up more
     * than one empty "New chat".
     */
    fun newConversation() {
        val active = currentActive()
        if (active != null && active.messages.isEmpty()) return // already on a blank chat
        _conversations.update { list -> list.filter { it.messages.isNotEmpty() } }
        val fresh = Conversation()
        _conversations.update { listOf(fresh) + it }
        _activeId.value = fresh.id
    }

    /** Open an existing conversation by id, dropping any empty draft left behind. */
    fun selectConversation(id: String) {
        if (_conversations.value.none { it.id == id }) return
        _conversations.update { list -> list.filter { it.id == id || it.messages.isNotEmpty() } }
        _activeId.value = id
    }

    /** Delete a conversation; if it was active, fall back to the most recent one. */
    suspend fun deleteConversation(id: String) {
        _conversations.update { list -> list.filterNot { it.id == id } }
        if (_activeId.value == id || _activeId.value == null) {
            val next = _conversations.value.maxByOrNull { it.updatedAt }
            if (next == null) {
                val fresh = Conversation()
                _conversations.value = listOf(fresh)
                _activeId.value = fresh.id
            } else {
                _activeId.value = next.id
            }
        }
        persist()
    }

    /** Rename a conversation (keeps its position; does not bump recency). */
    suspend fun renameConversation(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        _conversations.update { list ->
            list.map { if (it.id == id) it.copy(title = trimmed.take(60)) else it }
        }
        persist()
    }

    suspend fun saveRemoteConfig(config: RemoteEngine.Config) {
        remoteConfigStore.save(config)
        _cloudModelId.value = config.model
    }

    fun clearRemoteConfig() {
        remoteConfigStore.clear()
        _cloudModelId.value = null
        // The cloud model is gone; fall back to on-device for the chat picker.
        setPreferCloud(false)
    }

    /**
     * Return the effective prior history to feed the engine, keeping it within the
     * context budget. When it overflows, the oldest turns are folded into a rolling
     * summary generated **locally** (so cloud turns never receive extra history);
     * if no on-device model is available, we fall back to truncation (keep the
     * summary, if any, plus the most recent turns).
     */
    private suspend fun prepareHistory(convo: Conversation): List<Message> {
        val msgs = convo.messages
        val budget = (contextTokens() - REPLY_RESERVE_TOKENS).coerceAtLeast(MIN_BUDGET_TOKENS)
        val estTotal = msgs.sumOf { estTokens(it.text) } + estTokens(convo.summary.orEmpty())
        if (estTotal <= budget) {
            return summaryPrefix(convo.summary) + msgs.drop(convo.summarizedCount)
        }

        val recent = msgs.takeLast(RECENT_WINDOW)
        val olderUnsummarized = msgs.dropLast(RECENT_WINDOW).drop(convo.summarizedCount)
        if (olderUnsummarized.isEmpty() || !localModelLoaded()) {
            return summaryPrefix(convo.summary) + recent // truncate
        }

        val newSummary = runCatching { summarizeTurns(convo.summary, olderUnsummarized) }
            .onFailure { sg.act.domain.core.CrashReporting.record(it) }
            .getOrNull()
        if (newSummary.isNullOrBlank()) {
            return summaryPrefix(convo.summary) + recent // fallback to truncation
        }

        val newCount = (msgs.size - RECENT_WINDOW).coerceAtLeast(0)
        updateActive { it.copy(summary = newSummary, summarizedCount = newCount) }
        return summaryPrefix(newSummary) + recent
    }

    private fun summaryPrefix(summary: String?): List<Message> =
        if (summary.isNullOrBlank()) emptyList()
        else listOf(Message(role = Role.DOMAIN, text = "[Summary of earlier conversation]\n$summary"))

    private suspend fun summarizeTurns(previous: String?, turns: List<Message>): String {
        val transcript = turns.joinToString("\n") {
            (if (it.role == Role.USER) "User: " else "Assistant: ") + it.text
        }
        val prompt = buildString {
            append("Summarize the conversation below so it can continue with full ")
            append("context. Capture key facts, names, decisions and the user's goals ")
            append("in a few sentences.\n\n")
            if (!previous.isNullOrBlank()) append("Summary so far:\n").append(previous).append("\n\n")
            append("Messages:\n").append(transcript).append("\n\nUpdated summary:")
        }
        return localEngine.complete(prompt, emptyList()).trim()
    }

    /** Rough token estimate (~4 chars/token); the native side is the hard safety net. */
    private fun estTokens(text: String): Int = (text.length / 4) + 1

    private fun currentActive(): Conversation? =
        _conversations.value.firstOrNull { it.id == _activeId.value }

    /** Apply [transform] to the active conversation within the list. */
    private fun updateActive(transform: (Conversation) -> Conversation) {
        val id = _activeId.value ?: return
        _conversations.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private suspend fun persist() {
        // Don't write until the on-disk history has been restored, or an early
        // persist would overwrite it with a partial in-memory list.
        if (!restored) return
        // Never persist empty drafts, so abandoned "New chat"s don't accumulate.
        conversationStore.save(_conversations.value.filter { it.messages.isNotEmpty() })
    }

    /**
     * Smooth typewriter rendering for any token stream:
     * - When the char queue is empty (stream is trickling — local / slow server):
     *   no added delay; chars appear as fast as they arrive.
     * - When the queue has buffered chars (burst from cloud API): drain at
     *   [burstMs] per char so bursts render smoothly rather than all at once.
     * - After the upstream flow completes: drain any remaining buffer at
     *   [drainMs] per char so the last paragraph isn't held up.
     */
    private fun Flow<String>.smoothStream(
        burstMs: Long = 18L,
        drainMs: Long = 6L,
    ): Flow<String> = channelFlow {
        val queue = Channel<Char>(Channel.UNLIMITED)
        var done = false
        launch {
            try {
                collect { token -> token.forEach { queue.send(it) } }
            } finally {
                done = true
                queue.close()
            }
        }
        for (ch in queue) {
            send(ch.toString())
            delay(
                when {
                    done -> drainMs       // fast drain after stream ends
                    !queue.isEmpty -> burstMs  // smooth a buffered burst
                    else -> 0L            // trickling stream: no added delay
                },
            )
        }
    }

    private fun Conversation.addMessage(message: Message) = copy(
        messages = messages + message,
        updatedAt = System.currentTimeMillis(),
    )

    /** Replace the text of the most recent message, preserving its id/route. */
    private fun Conversation.updateLastText(text: String): Conversation {
        if (messages.isEmpty()) return this
        val updated = messages.toMutableList()
        updated[updated.lastIndex] = updated.last().copy(text = text)
        return copy(messages = updated, updatedAt = System.currentTimeMillis())
    }

    private fun Conversation.retitleIfNeeded(firstPrompt: String) =
        if (title == "New chat" && firstPrompt.isNotBlank()) {
            copy(title = firstPrompt.take(40))
        } else {
            this
        }

    private companion object {
        const val RECENT_WINDOW = 6          // turns kept verbatim
        const val REPLY_RESERVE_TOKENS = 768 // headroom for the reply + template
        const val MIN_BUDGET_TOKENS = 512
    }
}
