package sg.act.domain.core

import android.os.Build
import sg.act.domain.BuildConfig
import sg.act.domain.inference.Adaptive
import sg.act.domain.inference.AdaptivePlan
import sg.act.domain.inference.CpuVariant
import sg.act.domain.inference.DeviceSnapshot
import sg.act.domain.inference.ModelManager
import sg.act.domain.privacy.CpuFeatures
import sg.act.domain.privacy.DeviceCapabilities

/**
 * What the app has actually detected about this device and how it has decided to
 * run — the contents of Settings → System info.
 *
 * This exists because the inference path makes several decisions on the user's
 * behalf (thread count, context window, batch size, CPU kernels, GPU offload) and
 * every one of them used to be invisible. When on-device models broke on a
 * specific phone, answering "what does the app think this CPU is?" took a logcat
 * capture and a round trip. One screen is cheaper, and it is honest: if the
 * adaptive planner disagrees with the hardware spec, this says so and why.
 */
data class SystemInfo(
    /** e.g. `"Xiaomi 25062RN2DA"`. */
    val device: String,
    /** e.g. `"16 (API 36)"`. */
    val android: String,
    /** Primary supported ABI, e.g. `"arm64-v8a"`. */
    val abi: String,
    val cores: Int,
    /** `AT_HWCAP` as hex, or null when the auxiliary vector was unreadable. */
    val hwcapHex: String?,
    /** Decoded hwcap feature names, or null when unknown. */
    val cpuFeatures: String?,
    /** Whether the CPU advertises dot product. Null when the hwcap is unknown. */
    val hasDotprod: Boolean?,
    /**
     * The ggml CPU build this device's capabilities select. The engine ships one
     * per feature tier and loads the best this hardware can run, so this is the
     * single line that says whether dispatch did anything for you.
     */
    val cpuVariant: String,
    /** ggml's build-time feature line; empty until the engine has initialized. */
    val engineBuildFeatures: String,
    /** Registered ggml backends; empty until the engine has initialized. */
    val backends: String,
    val totalRamMb: Long,
    val availableRamMb: Long,
    /** Human-readable thermal status, e.g. `"none"`, `"severe"`, `"unknown"`. */
    val thermal: String,
    val powerSaveMode: Boolean,
    /** The plan in force, sampled at the same moment as everything above. */
    val plan: AdaptivePlan,
    /** Thread count the next load will use (the user's choice, or plan Auto). */
    val effectiveThreads: Int,
    /** Context the loaded model has, or that the next load will request. */
    val effectiveContextTokens: Int,
    /** True when the user has pinned an explicit thread count. */
    val threadsUserChosen: Boolean,
    /** True when the user has pinned an explicit context length. */
    val contextUserChosen: Boolean,
    val appVersion: String,
) {

    /**
     * A plain-text rendering for the clipboard, so this can be pasted into a bug
     * report. Deliberately **not** localized: it is diagnostic output meant to be
     * read by whoever fixes the bug, and stable English keys are easier to compare
     * across reports than translated ones.
     */
    fun report(): String = buildString {
        appendLine("Domain AI $appVersion")
        appendLine("device: $device")
        appendLine("android: $android")
        appendLine("abi: $abi")
        appendLine("cores: $cores")
        appendLine("hwcap: ${hwcapHex ?: "unknown"}")
        appendLine("cpu features: ${cpuFeatures ?: "unknown"}")
        appendLine("dotprod: ${hasDotprod?.toString() ?: "unknown"}")
        appendLine("cpu variant: $cpuVariant")
        appendLine("engine build: ${engineBuildFeatures.ifEmpty { "not initialized" }}")
        appendLine("backends: ${backends.ifEmpty { "not initialized" }}")
        appendLine("ram: ${availableRamMb} MB free of ${totalRamMb} MB")
        appendLine("thermal: $thermal")
        appendLine("power save: $powerSaveMode")
        appendLine(
            "threads: $effectiveThreads (" +
                (if (threadsUserChosen) "user" else "auto ${plan.autoThreads}") +
                ", max ${plan.maxThreads})",
        )
        appendLine(
            "context: $effectiveContextTokens (" +
                (if (contextUserChosen) "user" else "auto ${plan.autoContextTokens}") +
                ", max ${plan.maxContextTokens})",
        )
        appendLine("batch: ${plan.batchSize}")
        appendLine(
            "constraints: " +
                plan.constraints.joinToString(", ") { it.label }.ifEmpty { "none" },
        )
    }

    companion object {

        /**
         * Sample everything at once. Order matters slightly: the plan is taken from
         * the same [DeviceCapabilities.snapshot] readings shown alongside it, so the
         * panel can never display a plan that contradicts the memory figure next to
         * it.
         */
        fun collect(
            capabilities: DeviceCapabilities,
            modelManager: ModelManager,
        ): SystemInfo {
            val snapshot = capabilities.snapshot()
            val caps = CpuFeatures.deviceHwcaps()
            val hwcap = caps.hwcap
            return SystemInfo(
                device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                cores = snapshot.cores,
                hwcapHex = hwcap?.let { "0x" + java.lang.Long.toHexString(it) },
                cpuFeatures = hwcap?.let(CpuFeatures::decodeHwcap),
                hasDotprod = hwcap?.let { (it and HWCAP_ASIMDDP) != 0L },
                cpuVariant = CpuVariant.expectedFor(caps.hwcap, caps.hwcap2),
                engineBuildFeatures = modelManager.engineBuildFeatures(),
                backends = modelManager.backendInfo(),
                totalRamMb = snapshot.totalRamMb,
                availableRamMb = snapshot.availableRamMb,
                thermal = DeviceSnapshot.thermalName(snapshot.thermalStatus),
                powerSaveMode = snapshot.powerSaveMode,
                plan = Adaptive.plan(snapshot),
                effectiveThreads = modelManager.effectiveThreads(),
                effectiveContextTokens = modelManager.effectiveContextTokens(),
                threadsUserChosen = modelManager.threadCount() > 0,
                contextUserChosen = modelManager.contextTokens() > 0,
                appVersion = BuildConfig.VERSION_NAME,
            )
        }

        /** `HWCAP_ASIMDDP`, mirrored so the decision here needs no auxv re-read. */
        private const val HWCAP_ASIMDDP = 1L shl 20
    }
}
