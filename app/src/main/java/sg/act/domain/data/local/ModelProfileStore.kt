package sg.act.domain.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sg.act.domain.inference.ModelProfile
import sg.act.domain.inference.ProviderType

/**
 * Encrypted store for the list of saved [ModelProfile]s, the active profile id,
 * and source-level credentials (Space URL/token, OpenRouter key) that persist
 * across profile switches so users never have to re-enter them.
 *
 * Profiles are stored as flat key-value pairs keyed by UUID, avoiding any
 * dependency on a serialization framework.
 */
class ModelProfileStore(private val context: Context) {

    // Lazy, because building a hardware-backed master key and opening a Tink
    // keyset is not free, and eager construction put that cost in
    // Application.onCreate() — on the main thread, before the first frame.
    // AppContainer warms this on a background coroutine at startup, so the work
    // still happens early; it just no longer blocks the launch.
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "oracle_model_profiles",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _profiles = MutableStateFlow<List<ModelProfile>>(emptyList())
    val profiles: StateFlow<List<ModelProfile>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    @Volatile
    private var loaded = false

    /**
     * Read persisted state into the flows. Idempotent and safe from any thread.
     *
     * This used to be an `init` block, which made constructing the store — and
     * therefore constructing [AppContainer][sg.act.domain.AppContainer] — pay for
     * the keystore on the main thread at launch. Every mutator calls this first,
     * so a write can never persist a list assembled before the existing one was
     * read; [warmUp] gets it done early on a background coroutine regardless.
     */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        _profiles.value = loadProfiles()
        val id = prefs.getString(KEY_ACTIVE_ID, null)
        _activeProfileId.value = id.takeIf { id != null && _profiles.value.any { p -> p.id == id } }
        loaded = true
    }

    /** Do the keystore work and the first read now, off the critical path. */
    fun warmUp() = ensureLoaded()

    fun upsertProfile(profile: ModelProfile) {
        ensureLoaded()
        val updated = _profiles.value.filterNot { it.id == profile.id } + profile
        _profiles.value = updated
        persistProfiles(updated)
    }

    fun deleteProfile(id: String) {
        ensureLoaded()
        if (_activeProfileId.value == id) setActiveProfileId(null)
        val updated = _profiles.value.filterNot { it.id == id }
        _profiles.value = updated
        persistProfiles(updated)
        prefs.edit()
            .remove("${P}${id}_name").remove("${P}${id}_type")
            .remove("${P}${id}_base_url").remove("${P}${id}_api_key")
            .remove("${P}${id}_model").remove("${P}${id}_logs_data")
            .apply()
    }

    fun renameProfile(id: String, name: String) {
        ensureLoaded()
        val updated = _profiles.value.map { if (it.id == id) it.copy(name = name) else it }
        _profiles.value = updated
        persistProfiles(updated)
    }

    fun setActiveProfileId(id: String?) {
        ensureLoaded()
        _activeProfileId.value = id
        if (id != null) prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        else prefs.edit().remove(KEY_ACTIVE_ID).apply()
    }

    // ---- Space connection credentials (separate from profiles) ----

    val spaceUrl: String? get() = prefs.getString(KEY_SPACE_URL, null)?.takeIf { it.isNotBlank() }
    val spaceToken: String? get() = prefs.getString(KEY_SPACE_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveSpaceCredentials(url: String, token: String) {
        prefs.edit().putString(KEY_SPACE_URL, url).putString(KEY_SPACE_TOKEN, token).apply()
    }

    fun clearSpaceCredentials() {
        prefs.edit().remove(KEY_SPACE_URL).remove(KEY_SPACE_TOKEN).apply()
    }

    // ---- OpenRouter key ----

    val openRouterKey: String? get() = prefs.getString(KEY_OR_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveOpenRouterKey(key: String) {
        prefs.edit().putString(KEY_OR_KEY, key).apply()
    }

    fun clearOpenRouterKey() {
        prefs.edit().remove(KEY_OR_KEY).apply()
    }

    private fun loadProfiles(): List<ModelProfile> {
        val ids = prefs.getString(KEY_PROFILE_IDS, null)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: return emptyList()
        return ids.mapNotNull { id ->
            val name = prefs.getString("${P}${id}_name", null) ?: return@mapNotNull null
            val type = prefs.getString("${P}${id}_type", null)
                ?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            val baseUrl = prefs.getString("${P}${id}_base_url", null) ?: return@mapNotNull null
            val apiKey = prefs.getString("${P}${id}_api_key", null) ?: return@mapNotNull null
            val model = prefs.getString("${P}${id}_model", null) ?: return@mapNotNull null
            val logsData = prefs.getBoolean("${P}${id}_logs_data", false)
            ModelProfile(id, name, type, baseUrl, apiKey, model, logsData)
        }
    }

    private fun persistProfiles(profiles: List<ModelProfile>) {
        val editor = prefs.edit()
        editor.putString(KEY_PROFILE_IDS, profiles.joinToString(",") { it.id })
        for (p in profiles) {
            editor.putString("${P}${p.id}_name", p.name)
            editor.putString("${P}${p.id}_type", p.type.name)
            editor.putString("${P}${p.id}_base_url", p.baseUrl)
            editor.putString("${P}${p.id}_api_key", p.apiKey)
            editor.putString("${P}${p.id}_model", p.model)
            editor.putBoolean("${P}${p.id}_logs_data", p.logsData)
        }
        editor.apply()
    }

    private companion object {
        const val KEY_PROFILE_IDS = "profile_ids"
        const val KEY_ACTIVE_ID = "active_profile_id"
        const val KEY_SPACE_URL = "space_url"
        const val KEY_SPACE_TOKEN = "space_token"
        const val KEY_OR_KEY = "or_key"
        const val P = "p_"
    }
}
