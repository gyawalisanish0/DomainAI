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

    /**
     * Generation thread count, probed **once** at startup (CPU topology is fixed for
     * the device). Uses **most** of the cores but leaves headroom (1–2 cores) so the
     * UI and system stay responsive, and never drops below the performance-core
     * count. Performance cores are those above the slowest frequency cluster.
     *
     * This is deliberately broader than "big cores only": on an 8-core 4+4 it yields
     * **6** threads (not 4), scaling down on smaller CPUs and up to the cap on
     * higher-core flagships. The matmul barrier means the slowest thread bounds each
     * step, so the true optimum is device-specific — the in-app benchmark is the
     * final word; this is a strong default.
     */
    val recommendedThreads: Int = computeRecommendedThreads()

    private fun computeRecommendedThreads(): Int {
        val total = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs = (0 until total).mapNotNull { cpu ->
            runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull()
        }
        // Performance cores = those above the slowest (little) cluster. 0 when /sys
        // is unreadable or the CPU is single-tier (all cores same max frequency).
        val performance = if (freqs.size == total && freqs.isNotEmpty()) {
            val min = freqs.min()
            freqs.count { it > min }
        } else {
            0
        }
        // Reserve a couple of cores for the UI/system on bigger CPUs (one on small
        // ones), but never use fewer than the performance cores. Cap at 8 — mobile
        // matmul stops scaling beyond that.
        val reserve = if (total >= 6) 2 else 1
        val threads = maxOf(performance, total - reserve)
        return threads.coerceIn(2, minOf(total, 8))
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
