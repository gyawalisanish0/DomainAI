package sg.act.domain.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The per-reply speed line is assembled from these strings, so what this returns
 * is what the user reads under an answer.
 */
class NumbersTest {

    @Test
    fun `renders one decimal place`() {
        assertEquals("6.2", formatOneDecimal(6.2))
        assertEquals("18.0", formatOneDecimal(18.0))
        assertEquals("0.9", formatOneDecimal(0.9))
    }

    @Test
    fun `rounds half up at the tenth`() {
        assertEquals("6.3", formatOneDecimal(6.25))
        assertEquals("6.2", formatOneDecimal(6.24))
        // The classic binary-representation case: 0.1 + 0.2 is 0.30000000000000004.
        assertEquals("0.3", formatOneDecimal(0.1 + 0.2))
    }

    @Test
    fun `no measurement reads as zero rather than a wrong number`() {
        assertEquals("0.0", formatOneDecimal(0.0))
        assertEquals("0.0", formatOneDecimal(-1.0))
        assertEquals("0.0", formatOneDecimal(Double.NaN))
        assertEquals("0.0", formatOneDecimal(Double.POSITIVE_INFINITY))
    }

    /**
     * The whole reason this exists instead of `String.format("%.1f", x)`: that
     * call follows the default locale and would write "6,2" here.
     */
    @Test
    fun `uses a dot under a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("6.2", formatOneDecimal(6.2))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `carries into the whole part`() {
        assertEquals("10.0", formatOneDecimal(9.96))
        assertEquals("1.0", formatOneDecimal(0.95))
    }
}
