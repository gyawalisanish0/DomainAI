package sg.act.domain

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests. These exercise the real app end-to-end on an emulator or
 * device — the offline fallback responder answers without a downloaded model, so
 * no GGUF is required for the basic flow.
 *
 * Run with: ./gradlew connectedDebugAndroidTest  (needs a connected device/emulator).
 */
@RunWith(AndroidJUnit4::class)
class ChatSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun sending_a_message_produces_a_reply() {
        rule.onNodeWithText("Message Domain AI…").performTextInput("hello")
        rule.onNodeWithContentDescription("Send").performClick()

        // The offline fallback greets back; wait for the reply bubble to appear.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Hello", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun a_reply_can_be_regenerated_without_repeating_the_question() {
        rule.onNodeWithText("Message Domain AI…").performTextInput("hello")
        rule.onNodeWithContentDescription("Send").performClick()

        // Regenerate appears under the reply once generation finishes.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithContentDescription(REGENERATE).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(REGENERATE).performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithContentDescription(REGENERATE).fetchSemanticsNodes().isNotEmpty()
        }

        // The question is replayed, not re-asked: still exactly one "hello" bubble.
        assertEquals(1, rule.onAllNodesWithText("hello").fetchSemanticsNodes().size)
    }

    @Test
    fun history_drawer_can_start_a_new_chat() {
        rule.onNodeWithContentDescription("Chat history").performClick()
        rule.onNodeWithText("New chat").performClick()
    }

    private companion object {
        /** Content description of the per-reply regenerate control (see strings.xml). */
        const val REGENERATE = "Regenerate reply"
    }
}
