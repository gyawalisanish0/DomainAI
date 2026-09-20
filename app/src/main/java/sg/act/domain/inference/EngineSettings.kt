package sg.act.domain.inference

import android.content.Context

/**
 * User overrides for the engine's automatic decisions.
 *
 * Most of what the inference path does is decided for you: which CPU build to
 * load, how many threads to run, how big a context and prompt batch to ask for.
 * That is the right default — the alternative is asking people questions they
 * have no way to answer. But automatic is not the same as unquestionable, and
 * two things follow from that.
 *
 * The first is diagnosis. A suspected bug in an automatic mechanism is very hard
 * to confirm if the mechanism cannot be switched off. Prompt-cache reuse is the
 * clear case: if a reply ever looks like it was answering an earlier question,
 * being able to turn the cache off and try again settles in one attempt what
 * would otherwise be a long exchange of logs.
 *
 * The second is that the automatic answer is a heuristic, and heuristics are
 * wrong on some hardware. Someone who benchmarks their own phone and finds a
 * different batch size faster should be able to keep it.
 *
 * Backed by plain SharedPreferences like [ContextSettings] and [ThreadSettings],
 * so it can be read synchronously at each model load.
 */
class EngineSettings(context: Context) {

    private val prefs = context.getSharedPreferences("oracle_engine", Context.MODE_PRIVATE)

    /**
     * Whether a follow-up turn may reuse the part of the previous prompt that is
     * still a prefix of the new one.
     *
     * On by default, and it is the single largest saving available in a multi-turn
     * chat: without it every turn re-reads the entire conversation before writing
     * a new token, which on a CPU without dot-product kernels dominates everything
     * else. Off is for diagnosis, and for the rare template that rebuilds its
     * prompt in a way no prefix survives.
     */
    fun reusePromptCache(): Boolean = prefs.getBoolean(KEY_REUSE_CACHE, true)

    fun setReusePromptCache(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REUSE_CACHE, enabled).apply()
    }

    /**
     * Prompt batch size override, or 0 to follow the device-adaptive plan.
     *
     * Larger batches process a prompt in fewer decode passes but size the compute
     * buffer proportionally, so the best value depends on a memory/throughput
     * trade this app can only guess at. The in-app benchmark makes it measurable.
     */
    fun batchSize(): Int = prefs.getInt(KEY_BATCH, 0)

    fun setBatchSize(size: Int) {
        prefs.edit().putInt(KEY_BATCH, size.coerceAtLeast(0)).apply()
    }

    /**
     * Pin each worker to one specific core, rather than giving them all a mask of
     * the fast cores and letting the scheduler move them within it.
     *
     * Strict placement removes migrations and the cache traffic they cause, which
     * is the theoretical optimum once the thread count matches the big cluster.
     * The loose default exists because Android's scheduler is not a passive
     * participant — cpuset and EAS can override placement anyway, and sharing the
     * mask lets it do something sensible when it does.
     *
     * Off by default because it is a change to behaviour that has been shipping,
     * and the in-app benchmark can settle it in two runs on real hardware, which
     * beats a guess.
     */
    fun strictAffinity(): Boolean = prefs.getBoolean(KEY_STRICT_AFFINITY, false)

    fun setStrictAffinity(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRICT_AFFINITY, enabled).apply()
    }

    /**
     * Run the inference workers above normal scheduling priority.
     *
     * Generation competes with everything else on the device. Raising priority
     * gets the workers scheduled sooner and preempted less, which shows up as
     * steadier tokens per second — at the cost of making the rest of the system,
     * including this app's own UI, more likely to stutter while a reply streams.
     *
     * That trade is genuinely a matter of preference, which is why it is a setting
     * and not a heuristic.
     */
    fun highPriority(): Boolean = prefs.getBoolean(KEY_HIGH_PRIORITY, false)

    fun setHighPriority(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_PRIORITY, enabled).apply()
    }

    private companion object {
        const val KEY_STRICT_AFFINITY = "strict_affinity"
        const val KEY_HIGH_PRIORITY = "high_priority"
        const val KEY_REUSE_CACHE = "reuse_prompt_cache"
        const val KEY_BATCH = "batch_size"
    }
}
