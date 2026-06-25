package sg.act.domain.inference

import android.content.Context

/**
 * Persists the user's chosen inference thread count. Backed by plain
 * SharedPreferences (like [ContextSettings] / [GpuGuard]) so it can be read
 * synchronously at each model load. A value of 0 means "Auto" — defer to the
 * device-adaptive count.
 */
class ThreadSettings(context: Context) {

    private val prefs = context.getSharedPreferences("oracle_inference", Context.MODE_PRIVATE)

    /** Chosen thread count; 0 means Auto (device-adaptive). */
    fun chosenThreads(): Int = prefs.getInt(KEY_THREADS, 0)

    /** Set the chosen thread count; pass 0 for Auto. */
    fun setChosenThreads(count: Int) {
        prefs.edit().putInt(KEY_THREADS, count.coerceAtLeast(0)).apply()
    }

    private companion object {
        const val KEY_THREADS = "thread_count"
    }
}
