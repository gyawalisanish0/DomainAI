package sg.act.domain.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Who authored a message. */
enum class Role { USER, DOMAIN }

/**
 * Where an answer was produced. This is surfaced to the user on every Domain AI
 * reply so the data path is never ambiguous — a core privacy guarantee.
 */
enum class Route {
    /** Answered entirely on-device. No network was touched. */
    LOCAL,

    /** Answered by the opt-in cloud provider, with explicit user consent. */
    CLOUD,

    /** A request the privacy layer refused to send anywhere. */
    BLOCKED,
}

/**
 * How fast one on-device reply actually ran. Recorded per reply rather than
 * globally because the honest answer to "is this model too big for my phone?"
 * changes with prompt length, context setting and how warm the device is — a
 * single benchmark number in Settings cannot show that drift, and a line under
 * the reply that produced it can.
 *
 * Null on cloud replies, and on anything restored from a conversation saved
 * before this was recorded.
 */
@Serializable
data class GenerationStats(
    /** Time spent reading the prompt before the first token appeared, in ms. */
    val prefillMs: Long,
    /** Tokens generated. */
    val tokens: Int,
    /** Generation speed, in tokens per second. */
    val tokensPerSecond: Double,
)

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val route: Route = Route.LOCAL,
    /** For CLOUD replies: the redacted text that actually left the device. */
    val sentPayloadPreview: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    /** For on-device replies: how fast this one ran. */
    val stats: GenerationStats? = null,
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = DEFAULT_TITLE,
    val messages: List<Message> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Rolling summary of the oldest turns, folded in when the chat outgrows the context. */
    val summary: String? = null,
    /** How many leading [messages] are already covered by [summary]. */
    val summarizedCount: Int = 0,
) {
    companion object {
        /** Placeholder title carried until the first prompt names the chat. */
        const val DEFAULT_TITLE = "New chat"
    }
}
