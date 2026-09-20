package sg.act.domain.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** How a model arrived on the device. */
enum class ModelSource { DOWNLOAD, IMPORT }

/** Descriptor of the active on-device model. The bytes live on disk; this is the pointer. */
data class ModelDescriptor(
    val fileName: String,
    val displayName: String,
    val source: ModelSource,
    val sizeBytes: Long,
)

/**
 * Persists which model is active across launches. Mirrors [RemoteConfigStore]:
 * the descriptor is small but stored in EncryptedSharedPreferences for
 * consistency with Domain AI's encrypt-everything-at-rest posture.
 */
class ModelStore(private val context: Context) {

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
            "oracle_model",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Force the keystore/Tink work now, off the startup critical path. */
    fun warmUp() {
        prefs
    }

    fun load(): ModelDescriptor? {
        val fileName = prefs.getString(KEY_FILE, null) ?: return null
        val displayName = prefs.getString(KEY_NAME, null) ?: return null
        val source = prefs.getString(KEY_SOURCE, null)?.let(ModelSource::valueOf) ?: return null
        val size = prefs.getLong(KEY_SIZE, 0L)
        return ModelDescriptor(fileName, displayName, source, size)
    }

    fun save(descriptor: ModelDescriptor) {
        prefs.edit()
            .putString(KEY_FILE, descriptor.fileName)
            .putString(KEY_NAME, descriptor.displayName)
            .putString(KEY_SOURCE, descriptor.source.name)
            .putLong(KEY_SIZE, descriptor.sizeBytes)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_FILE = "file_name"
        const val KEY_NAME = "display_name"
        const val KEY_SOURCE = "source"
        const val KEY_SIZE = "size_bytes"
    }
}
