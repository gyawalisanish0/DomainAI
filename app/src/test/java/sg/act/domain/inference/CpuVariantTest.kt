package sg.act.domain.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuVariantTest {

    private companion object {
        const val DP = CpuVariant.HWCAP_ASIMDDP
        const val FP16 = CpuVariant.HWCAP_FPHP
        const val SVE = CpuVariant.HWCAP_SVE
        const val SVE2 = CpuVariant.HWCAP2_SVE2
        const val I8MM = CpuVariant.HWCAP2_I8MM
        const val SME = CpuVariant.HWCAP2_SME
    }

    // --- The device this whole feature was rebuilt around -----------------------

    @Test
    fun `a CPU without dot product gets only the baseline`() {
        // The maintainer's phone. It is why the fixed armv8_2 baseline was reverted:
        // every tier above baseline needs dotprod, so this is the entire list.
        val candidates = CpuVariant.candidatesFor(hwcap = FP16, hwcap2 = 0L)
        assertEquals(listOf(CpuVariant.BASELINE), candidates)
        assertEquals(CpuVariant.BASELINE, CpuVariant.expectedFor(FP16, 0L))
    }

    @Test
    fun `dispatch changes nothing for a device that has no features`() {
        assertEquals(listOf(CpuVariant.BASELINE), CpuVariant.candidatesFor(0L, 0L))
    }

    // --- Ordinary modern phones -------------------------------------------------

    @Test
    fun `dotprod alone reaches the first armv8_2 tier`() {
        assertEquals(
            listOf("ggml-cpu-android_armv8.2_1", CpuVariant.BASELINE),
            CpuVariant.candidatesFor(DP, 0L),
        )
    }

    @Test
    fun `dotprod with fp16 reaches the second armv8_2 tier`() {
        assertEquals("ggml-cpu-android_armv8.2_2", CpuVariant.expectedFor(DP or FP16, 0L))
    }

    @Test
    fun `adding i8mm reaches armv8_6`() {
        assertEquals("ggml-cpu-android_armv8.6_1", CpuVariant.expectedFor(DP or FP16, I8MM))
    }

    @Test
    fun `sve2 without sve still reaches armv9_0, which does not require sve`() {
        // Mirrors the upstream tier definition: android_armv9.0_1 asks for SVE2 but
        // not SVE. Encoding what upstream actually declares, not what seems tidy.
        assertEquals(
            "ggml-cpu-android_armv9.0_1",
            CpuVariant.expectedFor(DP or FP16, I8MM or SVE2),
        )
    }

    @Test
    fun `a full armv9_2 part reaches the top tier`() {
        assertEquals(
            "ggml-cpu-android_armv9.2_2",
            CpuVariant.expectedFor(DP or FP16 or SVE, I8MM or SVE2 or SME),
        )
    }

    @Test
    fun `sve and sme without sve2 land on the first armv9_2 tier`() {
        assertEquals(
            "ggml-cpu-android_armv9.2_1",
            CpuVariant.expectedFor(DP or FP16 or SVE, I8MM or SME),
        )
    }

    // --- Safety properties ------------------------------------------------------

    @Test
    fun `the baseline is always offered, whatever the capabilities`() {
        val words = listOf(0L, DP, DP or FP16, DP or FP16 or SVE, -1L)
        for (cap in words) for (cap2 in words) {
            val candidates = CpuVariant.candidatesFor(cap, cap2)
            assertTrue("cap=$cap cap2=$cap2", candidates.isNotEmpty())
            assertEquals("cap=$cap cap2=$cap2", CpuVariant.BASELINE, candidates.last())
        }
    }

    @Test
    fun `unreadable capability words fall back to the baseline, not to a guess`() {
        assertEquals(listOf(CpuVariant.BASELINE), CpuVariant.candidatesFor(null, null))
        assertEquals(CpuVariant.BASELINE, CpuVariant.expectedFor(null, null))
    }

    @Test
    fun `a missing hwcap2 never enables a tier that needs one of its bits`() {
        // i8mm, sve2 and sme all live in HWCAP2. With it unreadable, the best a
        // fully-featured CPU can reach is the armv8.2 pair.
        val candidates = CpuVariant.candidatesFor(DP or FP16 or SVE, null)
        assertEquals("ggml-cpu-android_armv8.2_2", candidates.first())
        assertTrue(candidates.none { it.contains("armv8.6") || it.contains("armv9") })
    }

    @Test
    fun `candidates are ordered most capable first and never repeat`() {
        val candidates = CpuVariant.candidatesFor(DP or FP16 or SVE, I8MM or SVE2 or SME)
        assertEquals(candidates.distinct(), candidates)
        assertEquals("ggml-cpu-android_armv9.2_2", candidates.first())
        assertEquals(CpuVariant.BASELINE, candidates.last())
        // every tier qualifies for a CPU with everything
        assertEquals(7, candidates.size)
    }

    @Test
    fun `soname wraps the library name the way the linker expects`() {
        assertEquals("libggml-cpu-android_armv8.0_1.so", CpuVariant.sonameOf(CpuVariant.BASELINE))
    }
}
