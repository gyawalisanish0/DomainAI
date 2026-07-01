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

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val route: Route = Route.LOCAL,
    /** For CLOUD replies: the redacted text that actually left the device. */
    val sentPayloadPreview: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<Message> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Rolling summary of the oldest turns, folded in when the chat outgrows the context. */
    val summary: String? = null,
    /** How many leading [messages] are already covered by [summary]. */
    val summarizedCount: Int = 0,
)
