package sg.act.domain.inference

/**
 * What the device looks like *right now*, as far as inference cares.
 *
 * Deliberately plain data with no Android types, so [Adaptive] can be exercised
 * on the JVM. [DeviceCapabilities][sg.act.domain.privacy.DeviceCapabilities]
 * fills it from the live system.
 */
data class DeviceSnapshot(
    /** Physical RAM, MB. Fixed for the life of the device. */
    val totalRamMb: Long,
    /** RAM the system says is available *now*, MB. Moves constantly. */
    val availableRamMb: Long,
    /** `ActivityManager.isLowRamDevice` — the vendor's own "this is a cheap phone". */
    val isLowRamDevice: Boolean,
    /** Online CPU count. */
    val cores: Int,
    /**
     * How many cores share the highest maximum clock — the "big" cluster — or 0
     * when the topology could not be read.
     *
     * This matters more than the total on a phone. ggml's threadpool synchronises
     * every worker at each barrier, so a batch finishes when its *slowest* thread
     * does. Spilling onto little cores therefore does not add throughput; it adds
     * a straggler that everyone else waits for.
     */
    val performanceCores: Int = 0,
    /** `PowerManager.getCurrentThermalStatus()`, or [THERMAL_UNKNOWN] below API 29. */
    val thermalStatus: Int = THERMAL_UNKNOWN,
    /** Battery saver is on. */
    val powerSaveMode: Boolean = false,
) {
    companion object {
        /** No thermal reading available (API < 29, or the call failed). */
        const val THERMAL_UNKNOWN = -1

        // Mirrors of PowerManager.THERMAL_STATUS_*, duplicated so this file stays
        // free of Android imports and testable on the JVM.
        const val THERMAL_NONE = 0
        const val THERMAL_LIGHT = 1
        const val THERMAL_MODERATE = 2
        const val THERMAL_SEVERE = 3
        const val THERMAL_CRITICAL = 4
        const val THERMAL_EMERGENCY = 5
        const val THERMAL_SHUTDOWN = 6

        /**
         * Name a thermal status for display and for the diagnostic report. Kept
         * here, beside the constants it names, so it stays free of Android types
         * and testable on the JVM.
         */
        fun thermalName(status: Int): String = when (status) {
            THERMAL_NONE -> "none"
            THERMAL_LIGHT -> "light"
            THERMAL_MODERATE -> "moderate"
            THERMAL_SEVERE -> "severe"
            THERMAL_CRITICAL -> "critical"
            THERMAL_EMERGENCY -> "emergency"
            THERMAL_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}

/** Why a plan came out smaller than this device's hardware alone would allow. */
enum class Constraint {
    /** The vendor flagged this as a low-RAM device. */
    LOW_RAM_DEVICE,

    /** Free memory right now, not total RAM, is what capped the plan. */
    FREE_MEMORY,

    /** The SoC is throttling; fewer threads finish sooner than more that stall. */
    THERMAL,

    /** Battery saver is on, so the user has asked for restraint. */
    POWER_SAVE,
    ;

    /** Short human-readable name, for the Settings panel and the bug report. */
    val label: String
        get() = when (this) {
            LOW_RAM_DEVICE -> "low-RAM device"
            FREE_MEMORY -> "free memory"
            THERMAL -> "thermal throttling"
            POWER_SAVE -> "battery saver"
        }
}

/**
 * The inference parameters this device should use at this moment.
 *
 * The `auto*` values are what "Auto" resolves to; the `max*` values bound what
 * the user may pick. The split matters, and is the one judgement call in here:
 *
 * - **Memory pressure moves the ceilings.** A context the phone cannot back with
 *   free RAM doesn't load, or loads and gets the process killed, so this clamps
 *   an explicit user choice too. Failing to honour a setting beats dying.
 * - **Thermal throttling and battery saver only bias Auto.** They are transient
 *   and the user may well have chosen 6 threads knowing the phone gets warm.
 *   Auto means "you decide, keep deciding"; a typed-in number does not.
 *
 * [constraints] exists so the Settings panel can say *why* — an adaptive system
 * that silently disagrees with the hardware spec is indistinguishable from a bug.
 */
data class AdaptivePlan(
    /** Thread count "Auto" resolves to now. */
    val autoThreads: Int,
    /** Largest thread count selectable on this device. */
    val maxThreads: Int,
    /** Context length "Auto" resolves to now. */
    val autoContextTokens: Int,
    /** Largest context length selectable, given current memory. */
    val maxContextTokens: Int,
    /** Prompt batch size (`n_batch`) for llama.cpp. */
    val batchSize: Int,
    /** Non-hardware reasons this plan is smaller than the device's ceiling. */
    val constraints: Set<Constraint> = emptySet(),
)

/**
 * Derives an [AdaptivePlan] from a [DeviceSnapshot]. Pure, so the whole policy is
 * unit-testable without a phone.
 *
 * Everything here is a **heuristic guardrail, not a computed footprint**: the real
 * KV-cache and compute-buffer sizes depend on the model's layer count and head
 * dimensions, which aren't known until it is opened. The thresholds are chosen so
 * they only bind when memory is genuinely tight — on a healthy phone the plan is
 * exactly what the RAM tier alone would give.
 */
object Adaptive {

    /**
     * The plan for [snapshot].
     *
     * Order matters: hardware tiers first, then the live clamps on top, so the
     * constraint set records precisely which live condition changed the answer.
     */
    fun plan(snapshot: DeviceSnapshot): AdaptivePlan {
        val constraints = mutableSetOf<Constraint>()
        if (snapshot.isLowRamDevice) constraints += Constraint.LOW_RAM_DEVICE

        // --- Threads -----------------------------------------------------------
        val cores = snapshot.cores.coerceAtLeast(1)
        val maxThreads = minOf(MAX_THREADS, cores).coerceAtLeast(MIN_THREADS)
        // Prefer the size of the big cluster, and fall back to half the cores when
        // the topology is unreadable — or when it reports exactly one top-clock
        // core, which means a prime-core design (1 + 3 + 4) rather than a cluster
        // of one. Taking that literally would run a single thread and leave the
        // three performance cores just below it idle.
        //
        // Half-the-cores was the old rule and it is wrong on the layout most phones
        // actually have. On a 4+4 device it happens to land on 4, which is right;
        // on a 2+6 it asks for 4 and two of those workers land on little cores,
        // where — because the threadpool waits for the slowest at every barrier —
        // they set the pace for all of them. Matching the big cluster keeps every
        // worker on comparable hardware.
        val baseThreads = if (snapshot.performanceCores >= MIN_THREADS) {
            snapshot.performanceCores.coerceIn(MIN_THREADS, maxThreads)
        } else {
            (cores / 2).coerceIn(MIN_THREADS, maxThreads)
        }
        val throttled = snapshot.thermalStatus >= DeviceSnapshot.THERMAL_SEVERE
        if (throttled) constraints += Constraint.THERMAL
        if (snapshot.powerSaveMode) constraints += Constraint.POWER_SAVE
        // Under either, halve Auto. Threads that spend their time stalled on a
        // throttled core add heat and contention without adding tokens.
        val autoThreads = if (throttled || snapshot.powerSaveMode) {
            (baseThreads / 2).coerceAtLeast(MIN_THREADS)
        } else {
            baseThreads
        }

        // --- Context -----------------------------------------------------------
        val contextTier = contextTierFor(snapshot)
        val maxContextTier = maxContextTierFor(snapshot)
        val freeCeiling = contextCeilingForFreeMemory(snapshot.availableRamMb)
        val maxContextTokens = minOf(maxContextTier, freeCeiling)
        if (freeCeiling < maxContextTier) constraints += Constraint.FREE_MEMORY
        val autoContextTokens = minOf(contextTier, maxContextTokens)

        // --- Prompt batch ------------------------------------------------------
        val batchTier = batchTierFor(snapshot.totalRamMb)
        val batchSize = if (snapshot.availableRamMb < TIGHT_MEMORY_MB) {
            constraints += Constraint.FREE_MEMORY
            minOf(batchTier, MIN_BATCH)
        } else {
            batchTier
        }

        return AdaptivePlan(
            autoThreads = autoThreads,
            maxThreads = maxThreads,
            autoContextTokens = autoContextTokens,
            maxContextTokens = maxContextTokens,
            batchSize = batchSize,
            constraints = constraints,
        )
    }

    /**
     * Context length to request, by total RAM. Larger contexts cost RAM (the KV
     * cache grows with `n_ctx`), so budget phones get a smaller window. The native
     * side additionally clamps to the model's trained context.
     */
    private fun contextTierFor(snapshot: DeviceSnapshot): Int = when {
        snapshot.isLowRamDevice || snapshot.totalRamMb < 3_000 -> 2048
        snapshot.totalRamMb < 6_000 -> 4096
        else -> 8192
    }

    /**
     * Ceiling on a user's context choice, by total RAM — roughly 2x the
     * recommendation: enough headroom to be useful, while still bounding the KV
     * cache so a high preset can't OOM a budget phone.
     */
    private fun maxContextTierFor(snapshot: DeviceSnapshot): Int = when {
        snapshot.isLowRamDevice || snapshot.totalRamMb < 3_000 -> 4096
        snapshot.totalRamMb < 6_000 -> 8192
        else -> 16384
    }

    /**
     * The largest context free memory can plausibly back right now. Total RAM is
     * the wrong question on a phone that is already holding six apps: a device
     * with 8 GB and 500 MB free is, for this purpose, a small device.
     */
    private fun contextCeilingForFreeMemory(availableRamMb: Long): Int = when {
        availableRamMb < 600 -> 2048
        availableRamMb < 1_200 -> 4096
        availableRamMb < 2_400 -> 8192
        else -> 16384
    }

    /**
     * Prompt-batch size by total RAM. Larger batches process the prompt in fewer
     * decode passes but size the compute buffer proportionally. The same
     * thresholds apply to the HF Space backend, so both sides agree.
     *
     *   < 8 GB   → 512   (budget/mid-range phones)
     *   8–16 GB  → 1024  (flagship phones / entry Space hardware)
     *   16–32 GB → 2048  (mid-range Space)
     *   32 GB+   → 4096  (high-memory Space / server)
     */
    private fun batchTierFor(totalRamMb: Long): Int = when {
        totalRamMb < 8_000 -> 512
        totalRamMb < 16_000 -> 1024
        totalRamMb < 32_000 -> 2048
        else -> 4096
    }

    /** Thread count never drops below this: one worker is slower than two. */
    const val MIN_THREADS = 2

    /**
     * Thread ceiling. Past six, contention with the UI and the system costs more
     * than the extra workers return on a phone.
     */
    const val MAX_THREADS = 6

    /** The batch floor, and the value a memory-pressured device is clamped to. */
    const val MIN_BATCH = 512

    /** Below this much free RAM, the prompt compute buffer is cut to [MIN_BATCH]. */
    const val TIGHT_MEMORY_MB = 1_500
}
