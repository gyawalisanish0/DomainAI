package sg.act.domain.ui.components

/**
 * One decimal place, built by hand rather than with `String.format`.
 *
 * `String.format("%.1f", x)` uses the default locale, which renders "6,2" in
 * much of Europe — next to a resource string written with a dot, and inside a
 * figure the user may well paste into a bug report. The separator here is part
 * of a fixed technical format, not prose, so it is deliberately not localised.
 *
 * Negative, NaN and infinite inputs all fall to "0.0": these come from timing a
 * generation, where those values mean "no measurement", not a real speed.
 */
internal fun formatOneDecimal(value: Double): String {
    if (!value.isFinite() || value <= 0.0) return "0.0"
    // Round half-up at the tenth, so 6.25 shows as 6.3 rather than 6.2.
    val tenths = kotlin.math.floor(value * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
