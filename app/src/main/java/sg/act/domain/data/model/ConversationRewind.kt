package sg.act.domain.data.model

/**
 * Rewinding a conversation to just before one of its user turns. This is the
 * shared mechanism behind both *regenerate* (re-answer the last question) and
 * *edit & resend* (change a question and take the conversation from there): in
 * both cases the turn and everything after it is dropped, and the prompt is then
 * sent again through the normal send path — so routing, redaction, history
 * budgeting and persistence all behave exactly as they do for a fresh message.
 *
 * Kept as pure functions on the model (no Android, no coroutines) so the
 * trimming rules are covered by JVM unit tests.
 */

/** A conversation trimmed back to just before a user turn, plus that turn's text. */
data class Rewind(
    /** The conversation with the rewound turn — and everything after it — removed. */
    val conversation: Conversation,
    /** The text of the removed user turn, ready to be sent again. */
    val prompt: String,
)

/**
 * Rewind to just before the most recent user turn, so it can be answered again.
 * Returns null when the conversation holds no user turn to replay.
 */
fun Conversation.rewindToLastUserTurn(): Rewind? =
    rewindAt(messages.indexOfLast { it.role == Role.USER })

/**
 * Rewind to just before the user message with [messageId], so an edited version
 * of it can be sent in its place. Returns null when no such user message exists
 * (it was already deleted, or the id names an assistant reply).
 */
fun Conversation.rewindToUserMessage(messageId: String): Rewind? =
    rewindAt(messages.indexOfFirst { it.id == messageId && it.role == Role.USER })

/** How many messages a rewind to [messageId] would discard after it, or 0 if none. */
fun Conversation.messagesAfter(messageId: String): Int {
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return 0
    return messages.size - index - 1
}

private fun Conversation.rewindAt(index: Int): Rewind? {
    if (index < 0) return null
    val kept = messages.take(index)
    return Rewind(
        conversation = copy(
            messages = kept,
            // The rolling summary can only claim messages that still exist. It may
            // now describe a little more than it covers (some summarized turns were
            // dropped), which is harmless: over-claiming keeps that text out of the
            // verbatim history instead of repeating it.
            summarizedCount = summarizedCount.coerceAtMost(kept.size),
            // Nothing is left to summarize once the chat is empty again.
            summary = if (kept.isEmpty()) null else summary,
            // An emptied chat is a blank chat: drop the title taken from the old
            // first prompt so the replacement prompt gets to name it.
            title = if (kept.isEmpty()) Conversation.DEFAULT_TITLE else title,
            // updatedAt is deliberately left alone — the resend that follows bumps it.
        ),
        prompt = messages[index].text,
    )
}
