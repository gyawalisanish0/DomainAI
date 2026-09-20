package sg.act.domain.privacy

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which CPU instruction-set features this device actually has.
 *
 * **This is diagnostic, not a gate.** The native build targets baseline
 * `armv8-a` (see `llama/src/main/cpp/CMakeLists.txt`), so every arm64 device can
 * run on-device models and nothing here refuses a load. v1.11 briefly compiled
 * for `armv8.2-a+dotprod+fp16` and had `ModelManager` check this first, to turn a
 * SIGILL on an older CPU into a clean explanation; the raised baseline was
 * reverted because it excluded the project's own primary test device, and the
 * check went with it.
 *
 * What remains is the measurement, logged at startup. It is the input any future
 * per-tier runtime dispatch needs, and it answers "does this phone have dotprod?"
 * from a bug report instead of a guess.
 *
 * The answer comes from **`/proc/self/auxv`**, the auxiliary vector the kernel
 * hands every process, read for its `AT_HWCAP` entry — the same bitmask
 * `getauxval(AT_HWCAP)` returns, and the authoritative source for ARM feature
 * bits. An earlier version parsed the `Features` line of `/proc/cpuinfo`
 * instead: that line is assembled by the vendor kernel with no guarantee it
 * lists `asimddp` even when the CPU implements it, whereas the hwcap bitmask has
 * a fixed meaning.
 *
 * Parsing is kept pure and separate from the file read so it can be unit-tested
 * on the JVM without a device.
 */
object CpuFeatures {

    /** `AT_HWCAP` — the auxv entry carrying the ARM feature bitmask. */
    private const val AT_HWCAP = 16L

    /**
     * `AT_HWCAP2` — the second bitmask. A separate word with its own bit meanings,
     * never an extension of the first: i8mm, SVE2 and SME live here, and bit 20
     * means something entirely different from the bit 20 of [AT_HWCAP].
     */
    private const val AT_HWCAP2 = 26L

    /** `AT_NULL` — terminates the auxiliary vector. */
    private const val AT_NULL = 0L

    /** `HWCAP_ASIMDDP`: the ARM dot-product extension (`sdot`/`udot`). */
    private const val HWCAP_ASIMDDP = 1L shl 20

    /**
     * Every bit AT_HWCAP defines for AArch64, per the kernel's
     * `arch/arm64/include/uapi/asm/hwcap.h` — for the debug log only. Decoding
     * the whole word rather than just the one bit this app cares about means a
     * single log line is enough to tell a real ARMv8.0 CPU (a short, plausible
     * list ending around `asimdrdm`) apart from a parsing bug (an empty or
     * nonsensical list) without needing a second round trip to ask for more.
     */
    private val KNOWN_BITS = listOf(
        0 to "fp", 1 to "asimd", 2 to "evtstrm", 3 to "aes", 4 to "pmull",
        5 to "sha1", 6 to "sha2", 7 to "crc32", 8 to "atomics", 9 to "fphp",
        10 to "asimdhp", 11 to "cpuid", 12 to "asimdrdm", 13 to "jscvt",
        14 to "fcma", 15 to "lrcpc", 16 to "dcpop", 17 to "sha3", 18 to "sm3",
        19 to "sm4", 20 to "asimddp", 21 to "sha512", 22 to "sve",
        23 to "asimdfhm", 24 to "dit", 25 to "uscat", 26 to "ilrcpc",
        27 to "flagm", 28 to "ssbs", 29 to "sb", 30 to "paca", 31 to "pacg",
    )

    private const val TAG = "CpuFeatures"

    /** Render a hwcap bitmask as space-separated feature names, for logging. */
    fun decodeHwcap(hwcap: Long): String =
        KNOWN_BITS.filter { (bit, _) -> (hwcap shr bit) and 1L != 0L }
            .joinToString(" ") { (_, name) -> name }
            .ifEmpty { "(none)" }

    /**
     * Pull `AT_HWCAP` out of a `/proc/self/auxv` image, or null when it isn't
     * present. The vector is a sequence of (type, value) pairs, each a native
     * unsigned long — 8 bytes little-endian on arm64 — ending at an [AT_NULL]
     * type.
     */
    fun hwcapFrom(auxv: ByteArray): Long? = auxvEntry(auxv, AT_HWCAP)

    /** The same, for [AT_HWCAP2] — where i8mm, SVE2 and SME are reported. */
    fun hwcap2From(auxv: ByteArray): Long? = auxvEntry(auxv, AT_HWCAP2)

    /** Walk the vector for one entry type, stopping at the terminator. */
    private fun auxvEntry(auxv: ByteArray, wanted: Long): Long? {
        val buffer = ByteBuffer.wrap(auxv).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= Long.SIZE_BYTES * 2) {
            val type = buffer.long
            val value = buffer.long
            when (type) {
                AT_NULL -> return null // end of the vector; the entry never appeared
                wanted -> return value
            }
        }
        return null // truncated or absent
    }

    /**
     * True when the auxv bitmask advertises dot-product support.
     *
     * Defaults to **true** when the bitmask can't be determined at all. Nothing
     * acts on the answer today, so the default costs nothing; it is kept this way
     * so that if a caller ever does gate on it, an unreadable auxv cannot disable
     * inference on a perfectly capable phone.
     */
    fun hasDotprod(auxv: ByteArray): Boolean {
        val hwcap = hwcapFrom(auxv) ?: return true
        return (hwcap and HWCAP_ASIMDDP) != 0L
    }

    /** Both capability words, either of which may be null when unreadable. */
    data class Hwcaps(val hwcap: Long?, val hwcap2: Long?)

    /**
     * This device's `AT_HWCAP` and `AT_HWCAP2`, from a single read of the
     * auxiliary vector. Null for either means it could not be determined — the
     * file was unreadable, or that entry was absent. Callers needing a decision
     * should treat null as "unknown", never as "no features".
     */
    fun deviceHwcaps(): Hwcaps = runCatching {
        val auxv = File("/proc/self/auxv").readBytes()
        Hwcaps(hwcapFrom(auxv), hwcap2From(auxv))
    }.getOrElse {
        Log.w(TAG, "Could not read /proc/self/auxv", it)
        Hwcaps(null, null)
    }

    /** Just `AT_HWCAP`, for callers that don't care about the second word. */
    fun deviceHwcap(): Long? = deviceHwcaps().hwcap

    /**
     * Read the live auxv; assumes capable if it can't be read. The resolved
     * bitmask is logged so a misfire is diagnosable from a bug report rather than
     * needing a guess about the device.
     */
    fun deviceHasDotprod(): Boolean {
        val hwcap = deviceHwcap()
        val supported = hwcap == null || (hwcap and HWCAP_ASIMDDP) != 0L
        Log.i(
            TAG,
            "AT_HWCAP=" + (hwcap?.let { "0x" + java.lang.Long.toHexString(it) } ?: "unknown") +
                " [" + (hwcap?.let(::decodeHwcap) ?: "n/a") + "]" +
                " dotprod=$supported",
        )
        return supported
    }
}
