package sg.act.domain.privacy

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import sg.act.domain.inference.Adaptive
import sg.act.domain.inference.AdaptivePlan
import sg.act.domain.inference.DeviceSnapshot

/**
 * The device's live capability readings: what the UI needs to recommend a model
 * the phone can actually run, and what [Adaptive] needs to size each load.
 *
 * Fixed properties ([totalRamMb], [coresBySpeed]) are probed once, since CPU
 * topology and physical RAM don't change. Everything else is read on demand:
 * free memory, thermal status and battery saver all move while the app is open,
 * and a plan derived once at startup would still be quoting them hours later.
 */
class DeviceCapabilities(context: Context) {

    enum class Suitability { RECOMMENDED, HEAVY, INSUFFICIENT }

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    val totalRamMb: Long = memoryInfo().totalMem / BYTES_PER_MB

    val isLowRam: Boolean = activityManager.isLowRamDevice

    /** RAM the system reports available right now, MB. */
    fun availableRamMb(): Long = memoryInfo().availMem / BYTES_PER_MB

    /**
     * Current thermal throttling level (`PowerManager.THERMAL_STATUS_*`), or
     * [DeviceSnapshot.THERMAL_UNKNOWN] below API 29 where there is no such API.
     */
    fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager.currentThermalStatus }
                .getOrDefault(DeviceSnapshot.THERMAL_UNKNOWN)
        } else {
            DeviceSnapshot.THERMAL_UNKNOWN
        }

    /** Whether battery saver is on. */
    fun powerSaveMode(): Boolean = runCatching { powerManager.isPowerSaveMode }.getOrDefault(false)

    /** Everything [Adaptive] reasons about, sampled now. */
    fun snapshot(): DeviceSnapshot = DeviceSnapshot(
        totalRamMb = totalRamMb,
        availableRamMb = availableRamMb(),
        isLowRamDevice = isLowRam,
        cores = Runtime.getRuntime().availableProcessors(),
        performanceCores = performanceCores,
        thermalStatus = thermalStatus(),
        powerSaveMode = powerSaveMode(),
    )

    /**
     * The inference plan for this moment. Call it at each model load rather than
     * caching it — that re-evaluation is the whole point.
     */
    fun plan(): AdaptivePlan = Adaptive.plan(snapshot())

    /**
     * All core indices ordered **fastest first** (empty if `/sys` is unreadable).
     *
     * Lazy: this reads one `/sys` file per core, which does not belong on the
     * main thread during launch. Topology never changes, so it is computed once
     * on whichever thread asks first — in practice the background model load.
     * The inference threadpool pins to the first `threads` of these, so a smaller
     * thread count naturally keeps generation on the primary/big cores. Pinning is
     * best-effort — Android's cpuset/EAS scheduler may override it.
     */
    val coresBySpeed: IntArray by lazy { topology.bySpeed }

    /**
     * How many cores run at the highest maximum clock, or 0 when `/sys` could not
     * be read. Derived from the same pass as [coresBySpeed], so the topology is
     * read once.
     */
    val performanceCores: Int by lazy { topology.performanceCores }

    private data class Topology(val bySpeed: IntArray, val performanceCores: Int)

    private val topology: Topology by lazy { computeTopology() }

    private fun memoryInfo(): ActivityManager.MemoryInfo =
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

    private fun computeTopology(): Topology {
        val total = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val freqs: List<Long?> = (0 until total).map { cpu ->
            runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.any { it == null }) return Topology(IntArray(0), 0)
        val known = freqs.map { it!! }
        val fastest = known.max()
        return Topology(
            bySpeed = (0 until total).sortedByDescending { known[it] }.toIntArray(),
            // A phone with one cluster reports every core at the same clock, which
            // correctly yields "all of them" — the plan's own ceiling then applies.
            performanceCores = known.count { it == fastest },
        )
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

    private companion object {
        const val BYTES_PER_MB = 1024L * 1024L
    }
}
