package sg.act.domain.privacy

import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which CPU instruction-set features this device actually has.
 *
 * The native library is compiled for `armv8.2-a+dotprod`, so ggml's accelerated
 * integer kernels — and anything else the compiler chose to vectorise — may use
 * instructions an ARMv8.0 arm64 CPU does not implement. Those would raise SIGILL
 * at an arbitrary point rather than fail cleanly, so the app checks for them up
 * front and stays on its offline responder instead of dying mid-reply.
 *
 * The answer comes from **`/proc/self/auxv`**, the auxiliary vector the kernel
 * hands every process, read for its `AT_HWCAP` entry — the same bitmask
 * `getauxval(AT_HWCAP)` returns, and the authoritative source for ARM feature
 * bits. An earlier version of this parsed the `Features` line of
 * `/proc/cpuinfo` instead and **wrongly rejected a capable device**: that line is
 * assembled by the vendor kernel and there is no guarantee it lists `asimddp`
 * even when the CPU implements it. The hwcap bitmask has a fixed meaning.
 *
 * Parsing is kept pure and separate from the file read so it can be unit-tested
 * on the JVM without a device.
 */
object CpuFeatures {

    /** `AT_HWCAP` — the auxv entry carrying the ARM feature bitmask. */
    private const val AT_HWCAP = 16L

    /** `AT_NULL` — terminates the auxiliary vector. */
    private const val AT_NULL = 0L

    /** `HWCAP_ASIMDDP`: the ARM dot-product extension (`sdot`/`udot`). */
    private const val HWCAP_ASIMDDP = 1L shl 20

    private const val TAG = "CpuFeatures"

    /**
     * Pull `AT_HWCAP` out of a `/proc/self/auxv` image, or null when it isn't
     * present. The vector is a sequence of (type, value) pairs, each a native
     * unsigned long — 8 bytes little-endian on arm64 — ending at an [AT_NULL]
     * type.
     */
    fun hwcapFrom(auxv: ByteArray): Long? {
        val buffer = ByteBuffer.wrap(auxv).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= Long.SIZE_BYTES * 2) {
            val type = buffer.long
            val value = buffer.long
            when (type) {
                AT_NULL -> return null // end of the vector; AT_HWCAP never appeared
                AT_HWCAP -> return value
            }
        }
        return null // truncated or absent
    }

    /**
     * True when the auxv bitmask advertises dot-product support.
     *
     * Defaults to **true** when the bitmask can't be determined at all, because a
     * false negative disables on-device inference on a perfectly capable phone —
     * which is a worse outcome than the SIGILL this guards against on the small
     * and shrinking set of ARMv8.0 arm64 parts. That exact false negative is why
     * this no longer reads `/proc/cpuinfo`.
     */
    fun hasDotprod(auxv: ByteArray): Boolean {
        val hwcap = hwcapFrom(auxv) ?: return true
        return (hwcap and HWCAP_ASIMDDP) != 0L
    }

    /**
     * Read the live auxv; assumes capable if it can't be read. The resolved
     * bitmask is logged so a misfire is diagnosable from a bug report rather than
     * needing a guess about the device.
     */
    fun deviceHasDotprod(): Boolean = runCatching {
        val auxv = File("/proc/self/auxv").readBytes()
        val hwcap = hwcapFrom(auxv)
        val supported = hasDotprod(auxv)
        Log.i(
            TAG,
            "AT_HWCAP=" + (hwcap?.let { "0x" + java.lang.Long.toHexString(it) } ?: "absent") +
                " dotprod=" + supported,
        )
        supported
    }.getOrElse {
        Log.w(TAG, "Could not read /proc/self/auxv; assuming dotprod is supported", it)
        true
    }
}
