package sg.act.domain.privacy

/**
 * Which CPU instruction-set features this device actually has.
 *
 * The native library is compiled for `armv8.2-a+dotprod`, so ggml's accelerated
 * integer kernels — and anything else the compiler chose to vectorise — may use
 * instructions an ARMv8.0 arm64 CPU does not implement. Those would raise SIGILL
 * at an arbitrary point rather than fail cleanly, so the app checks for them up
 * front and stays on its offline responder instead of dying mid-reply.
 *
 * Parsing is kept pure and separate from the file read so it can be unit-tested
 * on the JVM without a device.
 */
object CpuFeatures {

    /** Linux's hwcap name for the ARM dot-product extension (`sdot`/`udot`). */
    private const val DOTPROD_FLAG = "asimddp"

    /**
     * True when `/proc/cpuinfo` reports dot-product support.
     *
     * The file lists a `Features` line per core (`Features : fp asimd ... asimddp`).
     * A big.LITTLE device reports one line per core and they can differ, so any
     * core advertising the flag is taken as support — the kernel would not expose
     * it on a core that lacks it, and generation is pinned to the big cores anyway.
     *
     * Defaults to **true** when the flag can't be determined at all (no `Features`
     * line — some kernels omit it, and Android 10+ can restrict `/proc` access).
     * A false negative would disable on-device inference on a perfectly capable
     * phone, which is worse than the SIGILL this guards against on the shrinking
     * set of pre-2017 parts.
     */
    fun hasDotprod(cpuinfo: String): Boolean {
        val featureLines = cpuinfo.lineSequence()
            .filter { it.substringBefore(':').trim().equals("Features", ignoreCase = true) }
            .toList()
        if (featureLines.isEmpty()) return true // undeterminable — assume capable
        return featureLines.any { line ->
            line.substringAfter(':')
                .split(' ', '\t')
                .any { it.trim().equals(DOTPROD_FLAG, ignoreCase = true) }
        }
    }

    /** Read the live `/proc/cpuinfo`; assumes capable if it can't be read. */
    fun deviceHasDotprod(): Boolean =
        runCatching { hasDotprod(java.io.File("/proc/cpuinfo").readText()) }.getOrDefault(true)
}
