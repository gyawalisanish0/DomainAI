package sg.act.domain.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the Domain AI self-hosted Space backend.
 *
 * Talks to the FastAPI server running llama.cpp inside an HF Docker Space.
 * Handles /health, /v1/catalog, and /v1/admin/load (SSE streaming).
 */
class SpaceClient {

    data class CatalogModel(
        val id: String,
        val name: String,
        val family: String,
        val repoId: String,
        val filename: String,
        val minRamMb: Int,
        val sizeMb: Int,
        val suitability: Suitability,
        val cached: Boolean,
    )

    enum class Suitability { RECOMMENDED, HEAVY, INSUFFICIENT }

    sealed class LoadEvent {
        data class Downloading(val pct: Int) : LoadEvent()
        object Cached : LoadEvent()
        object Loading : LoadEvent()
        data class Ready(val model: String) : LoadEvent()
        data class Error(val message: String) : LoadEvent()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // no timeout for long SSE streams
        .build()

    data class HealthStatus(
        val reachable: Boolean,
        val modelLoaded: Boolean = false,
        val loadingInProgress: Boolean = false,
        val loadError: String? = null,
        val modelLabel: String? = null,
    )

    /** Returns true if the Space is reachable and responding to /health. */
    suspend fun checkHealth(spaceUrl: String, token: String): Boolean =
        fetchHealth(spaceUrl, token).reachable

    /** Returns detailed health status from the Space /health endpoint. */
    suspend fun fetchHealth(spaceUrl: String, token: String): HealthStatus =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${spaceUrl.trimEnd('/')}/health")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching HealthStatus(reachable = false)
                    val body = resp.body?.string() ?: return@runCatching HealthStatus(reachable = true)
                    val obj = JSONObject(body)
                    HealthStatus(
                        reachable = true,
                        modelLoaded = obj.optBoolean("model_loaded", false),
                        loadingInProgress = obj.optBoolean("loading_in_progress", false),
                        loadError = obj.optString("load_error").takeIf { it.isNotEmpty() && it != "null" },
                        modelLabel = obj.optString("model").takeIf { it.isNotEmpty() && it != "null" },
                    )
                }
            }.getOrDefault(HealthStatus(reachable = false))
        }

    /** Fetches the curated model catalog from the Space, with suitability ratings. */
    suspend fun fetchCatalog(spaceUrl: String, token: String): List<CatalogModel> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("${spaceUrl.trimEnd('/')}/v1/catalog")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("Catalog fetch failed: ${resp.code}")
                resp.body?.string() ?: "[]"
            }
            val arr = JSONArray(body)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CatalogModel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    family = obj.getString("family"),
                    repoId = obj.getString("repo_id"),
                    filename = obj.getString("filename"),
                    minRamMb = obj.getInt("min_ram_mb"),
                    sizeMb = obj.getInt("size_mb"),
                    suitability = when (obj.getString("suitability")) {
                        "HEAVY" -> Suitability.HEAVY
                        "INSUFFICIENT" -> Suitability.INSUFFICIENT
                        else -> Suitability.RECOMMENDED
                    },
                    cached = obj.optBoolean("cached", false),
                )
            }
        }

    /**
     * Tells the Space to download (if not cached) and load the given model.
     * Emits [LoadEvent]s from the SSE stream — Downloading(pct), Cached, Loading,
     * Ready(model), or Error(message). Collect until the flow completes.
     */
    fun loadModel(
        spaceUrl: String,
        token: String,
        repoId: String,
        filename: String,
    ): Flow<LoadEvent> = flow {
        val json = JSONObject().apply {
            put("repo_id", repoId)
            put("filename", filename)
        }.toString()
        val req = Request.Builder()
            .url("${spaceUrl.trimEnd('/')}/v1/admin/load")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Load request failed: ${resp.code}")
            val source = resp.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                val obj = JSONObject(data)
                val event: LoadEvent = when (obj.getString("status")) {
                    "downloading" -> LoadEvent.Downloading(obj.getInt("pct"))
                    "cached"      -> LoadEvent.Cached
                    "loading"     -> LoadEvent.Loading
                    "ready"       -> LoadEvent.Ready(obj.getString("model"))
                    "error"       -> LoadEvent.Error(obj.optString("message", "Unknown error"))
                    else          -> continue
                }
                emit(event)
            }
        }
    }.flowOn(Dispatchers.IO)
}
