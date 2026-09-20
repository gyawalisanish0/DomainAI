package sg.act.domain.inference

/** Identifies the source type of a saved cloud inference profile. */
enum class ProviderType { SPACE, OPEN_ROUTER, CUSTOM }

/**
 * A saved, named inference configuration for a remote model.
 * One profile is "active" at a time; switching is instant — no re-entry of
 * credentials required. Local models are tracked by [ModelManager] instead.
 */
data class ModelProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val logsData: Boolean = false,
) {
    fun toRemoteConfig() = RemoteEngine.Config(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        logsData = logsData,
    )

    companion object {
        fun autoName(type: ProviderType, model: String, baseUrl: String): String {
            val shortModel = model.substringAfterLast('/').removeSuffix(":free").take(30)
            val suffix = when (type) {
                ProviderType.SPACE -> "My server"
                ProviderType.OPEN_ROUTER -> "OpenRouter"
                ProviderType.CUSTOM -> baseUrl
                    .removePrefix("https://").removePrefix("http://")
                    .substringBefore('/').substringBefore(':').take(20)
            }
            return "$shortModel · $suffix"
        }
    }
}
