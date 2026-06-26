package sg.act.domain.privacy

import android.app.ActivityManager
import android.content.Context

/**
 * Reads the device's memory profile so the UI can recommend an on-device model
 * the phone can actually run — rather than shipping a one-size default that OOMs
 * on budget hardware.
 */
class DeviceCapabilities(context: Context) {

    enum class Suitability { RECOMMENDED, HEAVY, INSUFFICIENT }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val totalRamMb: Long = ActivityManager.MemoryInfo().also {
        activityManager.getMemoryInfo(it)
    }.totalMem / (1024 * 1024)

    val isLowRam: Boolean = activityManager.isLowRamDevice

    private val cpuProfile = computeCpuProfile()

    /**
     * Device-adaptive **Auto** thread count, probed **once** at startup (CPU topology
     * is fixed). A deliberate **middle ground**: roughly **half** the cores, so
     * generation gets a solid share of the CPU while the rest stays free for the UI
     * and system. Bounded to `2..6`. On an 8-core phone this yields 4; smaller CPUs
     * scale down. The user can raise it (up to [maxThreads]) in Settings / the chat
     * quick-panel.
     */
    val recommendedThreads: Int = cpuProfile.first

    /** Largest thread count the user may select on this device: `2..min(6, cores)`. */
    val maxThreads: Int = minOf(6, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)

    /**
     * All core indices ordered **fastest first** (empty if `/sys` is unreadable). The
     * inference threadpool pins to the first `effectiveThreads` of these, so picking a
     * smaller thread count naturally keeps generation on the primary/big cores.
     * Pinning is best-effort — Android's cpuset/EAS scheduler may override it.
     */
    val coresBySpeed: IntArray = cpuProfile.second

    private fun computeCpuProfile(): Pair<Int, IntArray> {
        val total = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs: List<Long?> = (0 until total).map { cpu ->
            runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull()
        }
        val haveFreqs = freqs.all { it != null }
        // Auto = a middle-ground count: about half the cores, so generation gets a
        // solid share of the CPU while the rest stays free for the UI/system. Bounded
        // to 2..6 (an 8-core phone lands on 4; the user can raise it in Settings).
        val threads = (total / 2).coerceIn(2, minOf(total, 6))
        val sorted = if (haveFreqs) {
            (0 until total).sortedByDescending { freqs[it]!! }.toIntArray()
        } else {
            IntArray(0)
        }
        return threads to sorted
    }

    /**
     * Classify a model needing [minRamMb] against this device. A comfortable run
     * wants noticeable headroom over the model's working set, so "recommended"
     * requires ~1.6x the model's minimum.
     */
    fun rate(minRamMb: Int): Suitability = when {
        totalRamMb < minRamMb -> Suitability.INSUFFICIENT
        totalRamMb < minRamMb * 1.6 -> Suitability.HEAVY
        else -> Suitability.RECOMMENDED
    }

    /**
     * Context length to request for on-device inference, scaled to device memory.
     * Larger contexts cost RAM (the KV cache grows with n_ctx), so budget phones
     * get a smaller window. The native side further clamps this to the model's
     * trained context.
     */
    fun recommendedContextTokens(): Int = when {
        isLowRam || totalRamMb < 3_000 -> 2048
        totalRamMb < 6_000 -> 4096
        else -> 8192
    }

    /**
     * The largest context length the user may select on this device. Roughly 2x the
     * recommended size: enough headroom to be useful, while still bounding the KV
     * cache so a high preset can't OOM a budget phone. The native side additionally
     * clamps any request to the model's trained context.
     */
    fun maxAllowedContextTokens(): Int = when {
        isLowRam || totalRamMb < 3_000 -> 4096
        totalRamMb < 6_000 -> 8192
        else -> 16384
    }
}
