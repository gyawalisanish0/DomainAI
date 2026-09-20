package sg.act.domain.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import sg.act.domain.inference.RemoteEngine

/**
 * Stores the optional cloud-provider credentials in EncryptedSharedPreferences.
 * The API key is encrypted at rest with a Keystore-backed master key and is only
 * read when the user has explicitly enabled cloud mode.
 */
class RemoteConfigStore(private val context: Context) {

    // Lazy, because building a hardware-backed master key and opening a Tink
    // keyset is not free, and eager construction put that cost in
    // Application.onCreate() — on the main thread, before the first frame.
    // AppContainer warms these on a background coroutine at startup, so the work
    // still happens early; it just no longer blocks the launch.
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "oracle_remote_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): RemoteEngine.Config? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val apiKey = prefs.getString(KEY_API_KEY, null) ?: return null
        val model = prefs.getString(KEY_MODEL, null) ?: return null
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) return null
        return RemoteEngine.Config(baseUrl, apiKey, model, prefs.getBoolean(KEY_LOGS_DATA, false))
    }

    fun save(config: RemoteEngine.Config) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putBoolean(KEY_LOGS_DATA, config.logsData)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_LOGS_DATA = "logs_data"
    }
}
