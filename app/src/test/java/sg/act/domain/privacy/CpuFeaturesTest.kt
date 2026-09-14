package sg.act.domain.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuFeaturesTest {

    /** A core stanza as `/proc/cpuinfo` renders it on arm64. */
    private fun core(index: Int, features: String) = """
        processor	: $index
        BogoMIPS	: 38.40
        Features	: $features
        CPU implementer	: 0x41
    """.trimIndent()

    // Snapdragon 845-era and later: dot product present.
    private val v82Features = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp"

    // Snapdragon 835-era: ARMv8.0, no asimddp.
    private val v80Features = "fp asimd evtstrm aes pmull sha1 sha2 crc32"

    @Test
    fun `detects dotprod on an armv8_2 cpu`() {
        assertTrue(CpuFeatures.hasDotprod(core(0, v82Features)))
    }

    @Test
    fun `reports no dotprod on an armv8_0 cpu`() {
        assertFalse(CpuFeatures.hasDotprod(core(0, v80Features)))
    }

    @Test
    fun `any core advertising dotprod counts as support`() {
        // big.LITTLE kernels print one Features line per core; they can disagree.
        val mixed = core(0, v80Features) + "\n\n" + core(4, v82Features)
        assertTrue(CpuFeatures.hasDotprod(mixed))
    }

    @Test
    fun `all cores lacking dotprod reports no support`() {
        val uniform = core(0, v80Features) + "\n\n" + core(4, v80Features)
        assertFalse(CpuFeatures.hasDotprod(uniform))
    }

    @Test
    fun `assumes capable when no Features line is present`() {
        // Some kernels omit Features entirely; refusing inference there would
        // disable a capable phone, so the benefit of the doubt goes to the device.
        assertTrue(CpuFeatures.hasDotprod("processor\t: 0\nBogoMIPS\t: 38.40\n"))
        assertTrue(CpuFeatures.hasDotprod(""))
    }

    @Test
    fun `a substring of the flag is not a match`() {
        // "asimddphd" must not satisfy the asimddp check.
        assertFalse(CpuFeatures.hasDotprod(core(0, "fp asimd asimddphd")))
        // ...but the real flag next to similar names still matches.
        assertTrue(CpuFeatures.hasDotprod(core(0, "fp asimdhp asimddp asimdrdm")))
    }

    @Test
    fun `feature matching tolerates tabs and odd spacing`() {
        assertTrue(CpuFeatures.hasDotprod("Features\t:\tfp\tasimd\tasimddp\n"))
    }

    @Test
    fun `an unrelated line mentioning the flag is ignored`() {
        // Only a Features line counts — not model names or other keys.
        assertFalse(CpuFeatures.hasDotprod("Hardware\t: asimddp board\nFeatures\t: fp asimd\n"))
    }
}
