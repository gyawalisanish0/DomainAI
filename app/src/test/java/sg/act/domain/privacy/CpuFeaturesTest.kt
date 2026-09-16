package sg.act.domain.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CpuFeaturesTest {

    private companion object {
        const val AT_NULL = 0L
        const val AT_PAGESZ = 6L
        const val AT_HWCAP = 16L
        const val AT_HWCAP2 = 26L
        const val HWCAP_ASIMDDP = 1L shl 20
        /** A plausible arm64 bitmask with fp, asimd, aes, crc32 … and dotprod. */
        const val HWCAP_WITH_DP = 0x0000_0000_001F_FFFFL
        /** The same, minus the dot-product bit. */
        const val HWCAP_WITHOUT_DP = HWCAP_WITH_DP and HWCAP_ASIMDDP.inv()
    }

    /** Build a little-endian 64-bit auxv image from (type, value) pairs. */
    private fun auxv(vararg pairs: Pair<Long, Long>): ByteArray {
        val b = ByteBuffer.allocate(pairs.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        pairs.forEach { (t, v) -> b.putLong(t); b.putLong(v) }
        return b.array()
    }

    @Test
    fun `finds AT_HWCAP among other entries`() {
        val image = auxv(AT_PAGESZ to 4096L, AT_HWCAP to HWCAP_WITH_DP, AT_NULL to 0L)
        assertEquals(HWCAP_WITH_DP, CpuFeatures.hwcapFrom(image))
    }

    @Test
    fun `reports dotprod when the bit is set`() {
        assertTrue(CpuFeatures.hasDotprod(auxv(AT_HWCAP to HWCAP_WITH_DP, AT_NULL to 0L)))
    }

    @Test
    fun `reports no dotprod when the bit is clear`() {
        // Every other feature bit set, dotprod alone missing — an ARMv8.0 part.
        assertTrue(HWCAP_WITHOUT_DP != 0L) // sanity: the mask is otherwise populated
        assertFalse(CpuFeatures.hasDotprod(auxv(AT_HWCAP to HWCAP_WITHOUT_DP, AT_NULL to 0L)))
    }

    @Test
    fun `only the dotprod bit decides`() {
        assertTrue(CpuFeatures.hasDotprod(auxv(AT_HWCAP to HWCAP_ASIMDDP, AT_NULL to 0L)))
        assertFalse(CpuFeatures.hasDotprod(auxv(AT_HWCAP to 0L, AT_NULL to 0L)))
    }

    @Test
    fun `stops at the AT_NULL terminator`() {
        // An AT_HWCAP after the terminator is not part of the vector.
        val image = auxv(AT_NULL to 0L, AT_HWCAP to HWCAP_WITH_DP)
        assertNull(CpuFeatures.hwcapFrom(image))
    }

    @Test
    fun `assumes capable when AT_HWCAP is absent`() {
        // This is the safety valve: never disable inference on an unreadable answer.
        assertNull(CpuFeatures.hwcapFrom(auxv(AT_PAGESZ to 4096L, AT_NULL to 0L)))
        assertTrue(CpuFeatures.hasDotprod(auxv(AT_PAGESZ to 4096L, AT_NULL to 0L)))
    }

    @Test
    fun `assumes capable on an empty or truncated vector`() {
        assertTrue(CpuFeatures.hasDotprod(ByteArray(0)))
        assertTrue(CpuFeatures.hasDotprod(ByteArray(7)))   // shorter than one field
        assertTrue(CpuFeatures.hasDotprod(ByteArray(15)))  // half a pair
    }

    @Test
    fun `ignores AT_HWCAP2 which carries different bits`() {
        // i8mm/sve2 live in HWCAP2; bit 20 there means something else entirely, so
        // it must not be mistaken for dotprod.
        val image = auxv(AT_HWCAP2 to HWCAP_ASIMDDP, AT_HWCAP to 0L, AT_NULL to 0L)
        assertFalse(CpuFeatures.hasDotprod(image))
    }

    @Test
    fun `takes the first AT_HWCAP entry`() {
        val image = auxv(AT_HWCAP to HWCAP_WITH_DP, AT_HWCAP to 0L, AT_NULL to 0L)
        assertEquals(HWCAP_WITH_DP, CpuFeatures.hwcapFrom(image))
    }

    @Test
    fun `decodes a plausible ARMv8_2 feature list`() {
        // fp, asimd, aes, pmull, sha1, sha2, crc32, atomics, fphp, asimdhp,
        // cpuid, asimdrdm, asimddp — the set a real dotprod-capable Cortex-A
        // core reports, in kernel bit order.
        val decoded = CpuFeatures.decodeHwcap(HWCAP_WITH_DP)
        assertEquals(
            "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp " +
                "cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp",
            decoded,
        )
    }

    @Test
    fun `decodes zero as an explicit empty list, not a blank string`() {
        assertEquals("(none)", CpuFeatures.decodeHwcap(0L))
    }

    @Test
    fun `decodes a single high bit correctly`() {
        // Bit 31 exercises the Long-shift path at the top of the word, where an
        // Int-based implementation would have hit sign-extension trouble.
        assertEquals("pacg", CpuFeatures.decodeHwcap(1L shl 31))
    }
}
