package sg.act.domain.core

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin, privacy-respecting wrapper over Firebase Crashlytics.
 *
 * - No-ops entirely when Firebase is not configured (no google-services.json), so
 *   the app behaves identically — and ships no telemetry — without a Firebase
 *   project.
 * - Collection is opt-in: nothing is sent until [setEnabled] is called with the
 *   user's explicit crash-reporting consent. Only crash/error reports are sent;
 *   no analytics or usage tracking is wired in.
 *
 * **Firebase is not initialized until the user opts in.** Its automatic
 * `FirebaseInitProvider` is removed in the manifest, so nothing Firebase-related
 * runs at launch. For the default configuration — crash reporting off — the SDK
 * is never touched at all, which is both faster and a better match for an app
 * whose premise is that it does nothing you didn't ask for. When consent is
 * given, [setEnabled] initializes it on whatever background thread called in.
 *
 * The cost: a crash in the first moments of a launch, before the consent flow
 * has been read back and this has initialized, is not captured. That is an
 * acceptable trade for an opt-in diagnostic — and it is the same window the
 * provider-based init only partially covered anyway.
 */
object CrashReporting {

    @Volatile
    private var appContext: Context? = null

    /**
     * `null` until an initialization has been attempted; then true when Firebase
     * is configured and ready, false when there is no configuration to use.
     */
    @Volatile
    private var ready: Boolean? = null

    /** Remember the context. Deliberately does no Firebase work. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Initialize Firebase if it hasn't been, returning whether it is usable.
     *
     * Synchronized because consent mirroring and a recorded error can arrive
     * from different coroutines, and `initializeApp` should run once.
     */
    @Synchronized
    private fun ensureReady(): Boolean {
        ready?.let { return it }
        val context = appContext ?: return false
        val app = FirebaseApp.getApps(context).firstOrNull()
            ?: runCatching { FirebaseApp.initializeApp(context) }.getOrNull()
        return (app != null).also { ready = it }
    }

    /** Whether Firebase has already been initialized — never initializes it. */
    private fun readyIfInitialized(): Boolean = ready == true

    /**
     * Mirror the user's consent. Turning it on initializes Firebase; turning it
     * off never does, because there is nothing to disable on an SDK that was
     * never started.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (ensureReady()) FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
        } else if (readyIfInitialized()) {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
        }
    }

    // The recording entry points below never initialize Firebase: they are called
    // from the model-load path on every launch, and a breadcrumb is not a reason
    // to start an SDK the user has not opted into.

    /** Record a non-fatal error for diagnostics (only when configured and enabled). */
    fun record(throwable: Throwable) {
        if (!readyIfInitialized()) return
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }

    /** Add a breadcrumb to the timeline attached to the next crash/non-fatal. */
    fun log(message: String) {
        if (!readyIfInitialized()) return
        FirebaseCrashlytics.getInstance().log(message)
    }

    /** Attach a custom key so recorded events carry diagnostic context. */
    fun setKey(key: String, value: String) {
        if (!readyIfInitialized()) return
        FirebaseCrashlytics.getInstance().setCustomKey(key, value)
    }

    fun setKey(key: String, value: Long) {
        if (!readyIfInitialized()) return
        FirebaseCrashlytics.getInstance().setCustomKey(key, value)
    }

    fun setKey(key: String, value: Int) {
        if (!readyIfInitialized()) return
        FirebaseCrashlytics.getInstance().setCustomKey(key, value)
    }
}
