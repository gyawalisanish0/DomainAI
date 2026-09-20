package sg.act.domain.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRewindTest {

    private fun user(text: String) = Message(role = Role.USER, text = text)
    private fun reply(text: String) = Message(role = Role.DOMAIN, text = text)

    /** A two-exchange chat: Q1/A1/Q2/A2. */
    private fun chat(vararg messages: Message) = Conversation(
        title = "Q1",
        messages = messages.toList(),
    )

    @Test
    fun `regenerate replays the last question and drops its answer`() {
        val convo = chat(user("Q1"), reply("A1"), user("Q2"), reply("A2"))

        val rewind = requireNotNull(convo.rewindToLastUserTurn())

        assertEquals("Q2", rewind.prompt)
        assertEquals(listOf("Q1", "A1"), rewind.conversation.messages.map { it.text })
    }

    @Test
    fun `regenerate works when the answer never arrived`() {
        // A send that failed outright can leave the question as the final message.
        val convo = chat(user("Q1"), reply("A1"), user("Q2"))

        val rewind = requireNotNull(convo.rewindToLastUserTurn())

        assertEquals("Q2", rewind.prompt)
        assertEquals(listOf("Q1", "A1"), rewind.conversation.messages.map { it.text })
    }

    @Test
    fun `regenerate is unavailable with nothing to replay`() {
        assertNull(Conversation().rewindToLastUserTurn())
        // An opening greeting with no question behind it isn't replayable either.
        assertNull(chat(reply("A1")).rewindToLastUserTurn())
    }

    @Test
    fun `editing a question discards every later turn`() {
        val first = user("Q1")
        val convo = chat(first, reply("A1"), user("Q2"), reply("A2"))

        val rewind = requireNotNull(convo.rewindToUserMessage(first.id))

        assertEquals("Q1", rewind.prompt)
        assertEquals(emptyList<String>(), rewind.conversation.messages.map { it.text })
    }

    @Test
    fun `editing the last question keeps everything before it`() {
        val second = user("Q2")
        val convo = chat(user("Q1"), reply("A1"), second, reply("A2"))

        val rewind = requireNotNull(convo.rewindToUserMessage(second.id))

        assertEquals(listOf("Q1", "A1"), rewind.conversation.messages.map { it.text })
    }

    @Test
    fun `editing an unknown or non-user message is refused`() {
        val answer = reply("A1")
        val convo = chat(user("Q1"), answer)

        // An assistant reply is not editable — only the question that produced it.
        assertNull(convo.rewindToUserMessage(answer.id))
        assertNull(convo.rewindToUserMessage("no-such-id"))
    }

    @Test
    fun `emptying a chat clears its title so the new prompt renames it`() {
        val first = user("Q1")
        val convo = chat(first, reply("A1"))

        val rewind = requireNotNull(convo.rewindToUserMessage(first.id))

        assertEquals(Conversation.DEFAULT_TITLE, rewind.conversation.title)
    }

    @Test
    fun `a chat that keeps messages keeps its title`() {
        val second = user("Q2")
        val convo = chat(user("Q1"), reply("A1"), second, reply("A2"))

        val rewind = requireNotNull(convo.rewindToUserMessage(second.id))

        assertEquals("Q1", rewind.conversation.title)
    }

    @Test
    fun `the rolling summary survives a rewind that keeps summarized turns`() {
        val convo = chat(user("Q1"), reply("A1"), user("Q2"), reply("A2"))
            .copy(summary = "earlier chat", summarizedCount = 2)

        val rewind = requireNotNull(convo.rewindToLastUserTurn())

        assertEquals("earlier chat", rewind.conversation.summary)
        assertEquals(2, rewind.conversation.summarizedCount)
    }

    @Test
    fun `summarizedCount can never outrun the messages that remain`() {
        val convo = chat(user("Q1"), reply("A1"), user("Q2"), reply("A2"))
            .copy(summary = "earlier chat", summarizedCount = 4)

        val rewind = requireNotNull(convo.rewindToLastUserTurn())

        assertEquals(2, rewind.conversation.messages.size)
        assertEquals(2, rewind.conversation.summarizedCount)
    }

    @Test
    fun `emptying a chat drops the rolling summary too`() {
        val first = user("Q1")
        val convo = chat(first, reply("A1"))
            .copy(summary = "earlier chat", summarizedCount = 1)

        val rewind = requireNotNull(convo.rewindToUserMessage(first.id))

        assertNull(rewind.conversation.summary)
        assertEquals(0, rewind.conversation.summarizedCount)
    }

    @Test
    fun `a rewind preserves the conversation's identity`() {
        val convo = chat(user("Q1"), reply("A1"), user("Q2"), reply("A2"))

        val rewind = requireNotNull(convo.rewindToLastUserTurn())

        assertEquals(convo.id, rewind.conversation.id)
        assertEquals(convo.updatedAt, rewind.conversation.updatedAt)
    }

    @Test
    fun `messagesAfter counts what an edit would discard`() {
        val first = user("Q1")
        val second = user("Q2")
        val last = reply("A2")
        val convo = chat(first, reply("A1"), second, last)

        assertEquals(3, convo.messagesAfter(first.id))
        assertEquals(1, convo.messagesAfter(second.id))
        assertEquals(0, convo.messagesAfter(last.id))
        assertEquals(0, convo.messagesAfter("no-such-id"))
    }
}
