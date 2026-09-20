package sg.act.domain.inference

/**
 * Which of ggml's per-CPU backend builds this device should use.
 *
 * ggml compiles its CPU backend once per feature tier when `GGML_CPU_ALL_VARIANTS`
 * is on, producing `libggml-cpu-android_armv8.0_1.so` … `…armv9.2_2.so`. Upstream
 * picks between them by globbing a directory, dlopening every candidate and asking
 * each to score itself. **That glob is the one thing that cannot work on Android**:
 * with `extractNativeLibs=false` (the default since minSdk 23) the `.so` files are
 * stored uncompressed *inside* the APK and mmap'd from there, so the directory the
 * registry scans contains nothing at all. That is precisely how a previous attempt
 * at this failed on device, with `no backends are loaded`.
 *
 * So this file owns the tier list instead. It reads the same hardware capability
 * bits ggml's own score function reads, and produces an ordered list of candidate
 * sonames — best first, baseline last — which the loader tries in turn. Naming the
 * libraries explicitly means the platform's own loader resolves them out of the
 * APK, with no filesystem scan anywhere.
 *
 * Two safety properties matter here:
 *
 *  1. **The list never contains a variant this CPU cannot run.** Loading a library
 *     built for a higher ISA is normally survivable — upstream deliberately builds
 *     the score function without architecture flags and with `-fno-lto` so it can't
 *     SIGILL — but not offering the library at all is stronger than relying on that.
 *  2. **The baseline is always last.** `android_armv8.0_1` requires nothing beyond
 *     the arm64 guarantees, so the list can never come back empty, and any device
 *     whose capabilities we misread still lands on something that runs.
 *
 * ggml re-checks each candidate's score as it loads, so a mistake here costs a
 * rejected load, not a crash.
 */
object CpuVariant {

    /** `HWCAP_ASIMDDP` — dot product (`sdot`/`udot`). */
    const val HWCAP_ASIMDDP = 1L shl 20

    /** `HWCAP_FPHP` — half-precision floating point. What ggml reads for fp16. */
    const val HWCAP_FPHP = 1L shl 9

    /** `HWCAP_SVE` — Scalable Vector Extension. */
    const val HWCAP_SVE = 1L shl 22

    /** `HWCAP2_SVE2`. */
    const val HWCAP2_SVE2 = 1L shl 1

    /** `HWCAP2_I8MM` — the int8 matrix-multiply extension (`smmla`). */
    const val HWCAP2_I8MM = 1L shl 13

    /** `HWCAP2_SME` — Scalable Matrix Extension. */
    const val HWCAP2_SME = 1L shl 23

    /** The variant every arm64 device can run. Always the final fallback. */
    const val BASELINE = "ggml-cpu-android_armv8.0_1"

    /**
     * One of ggml's Android tiers, with the features its build requires.
     *
     * The feature sets mirror `ggml_add_cpu_backend_variant(android_…)` in
     * `ggml/src/CMakeLists.txt` exactly. If that list changes upstream when the
     * vendored llama.cpp is bumped, this must change with it — a name that no
     * longer exists simply fails to load and falls through to the next candidate,
     * but a tier that exists and is never offered is silently wasted.
     */
    private data class Tier(
        val library: String,
        val dotprod: Boolean = false,
        val fp16: Boolean = false,
        val i8mm: Boolean = false,
        val sve: Boolean = false,
        val sve2: Boolean = false,
        val sme: Boolean = false,
    )

    /** Most capable first — the order candidates are offered to the loader. */
    private val TIERS = listOf(
        Tier("ggml-cpu-android_armv9.2_2", dotprod = true, fp16 = true, i8mm = true, sve = true, sve2 = true, sme = true),
        Tier("ggml-cpu-android_armv9.2_1", dotprod = true, fp16 = true, i8mm = true, sve = true, sme = true),
        Tier("ggml-cpu-android_armv9.0_1", dotprod = true, fp16 = true, i8mm = true, sve2 = true),
        Tier("ggml-cpu-android_armv8.6_1", dotprod = true, fp16 = true, i8mm = true),
        Tier("ggml-cpu-android_armv8.2_2", dotprod = true, fp16 = true),
        Tier("ggml-cpu-android_armv8.2_1", dotprod = true),
        Tier(BASELINE),
    )

    /**
     * Candidate libraries for a device with these capability words, best first.
     *
     * [hwcap] and [hwcap2] are `AT_HWCAP` / `AT_HWCAP2` from the auxiliary vector.
     * Pass null for either when it could not be read: unknown is treated as
     * "feature absent", which costs speed and never costs correctness.
     */
    fun candidatesFor(hwcap: Long?, hwcap2: Long?): List<String> {
        val cap = hwcap ?: 0L
        val cap2 = hwcap2 ?: 0L
        fun has(word: Long, bit: Long) = (word and bit) != 0L

        val dotprod = has(cap, HWCAP_ASIMDDP)
        val fp16 = has(cap, HWCAP_FPHP)
        val sve = has(cap, HWCAP_SVE)
        val sve2 = has(cap2, HWCAP2_SVE2)
        val i8mm = has(cap2, HWCAP2_I8MM)
        val sme = has(cap2, HWCAP2_SME)

        return TIERS.filter { tier ->
            (!tier.dotprod || dotprod) &&
                (!tier.fp16 || fp16) &&
                (!tier.i8mm || i8mm) &&
                (!tier.sve || sve) &&
                (!tier.sve2 || sve2) &&
                (!tier.sme || sme)
        }.map { it.library }
    }

    /**
     * The library that will actually be used — the first candidate, which is the
     * most capable tier this CPU satisfies. For display only; the loader decides
     * for real, and ggml's own score check can still reject this one.
     */
    fun expectedFor(hwcap: Long?, hwcap2: Long?): String =
        candidatesFor(hwcap, hwcap2).first()

    /** `libfoo.so` for a `System.loadLibrary`-style name. */
    fun sonameOf(library: String): String = "lib$library.so"
}
