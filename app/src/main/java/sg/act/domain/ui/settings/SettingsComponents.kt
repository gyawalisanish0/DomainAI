package sg.act.domain.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import sg.act.domain.R

/**
 * Building blocks for the Settings screen.
 *
 * The screen used to be one flat scroll: every section expanded at once, each
 * control carrying a permanent paragraph of explanation, sections separated by
 * full-width dividers under large coloured headings. All of the app's
 * configuration surface was on screen simultaneously, so nothing looked more
 * important than anything else and the prose drowned the controls.
 *
 * These pieces impose the opposite: **grouped surfaces, one line per row, and
 * explanation on request.** A row says what it is; a section says what state
 * it's in while collapsed; the reasoning lives behind [SettingsDisclosure] for
 * the people who want it. Nothing is deleted — it stops being mandatory reading.
 */

/**
 * A section heading. Small, quiet and outdented slightly from the group below
 * it — M3 treats these as labels, not titles, which is what stops a settings
 * screen reading like a document with chapters.
 */
@Composable
fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = dimensionResource(R.dimen.space_l),
            top = dimensionResource(R.dimen.space_s),
            bottom = dimensionResource(R.dimen.space_xs),
        ),
    )
}

/**
 * A rounded container holding related rows. Grouping is what separates sections,
 * so the screen needs no dividers between them — the shape does that job, and
 * does it without drawing a line across every 16dp of the display.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensionResource(R.dimen.group_corner)))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/**
 * A switch row. [subtitle] is optional and deliberately hard to justify: if the
 * title doesn't say what the control does, the title is wrong. Reserve it for
 * the cases where the consequence genuinely isn't in the name.
 *
 * The whole row toggles, not just the switch — a 48dp target at the far right of
 * the screen is the hardest thing to hit one-handed.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(
                horizontal = dimensionResource(R.dimen.space_l),
                vertical = dimensionResource(R.dimen.space_m),
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_l)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // null: the row already handles the gesture, and a switch that also
        // handled it would double-toggle on a tap that lands on both.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A section that collapses to a single row.
 *
 * Collapsed, it shows [status] — what this section's state currently *is*, so
 * the answer to "am I connected?" or "which model is loaded?" needs no tap.
 * That is the whole trade: you give up permanent visibility of the controls and
 * get a screen you can read at a glance.
 *
 * [initiallyExpanded] is for the section people actually came for. The state is
 * saved, so it survives rotation and process death.
 *
 * [onExpand] fires on the transition to open — for a section whose contents are
 * a live reading rather than a stored setting, opening it is exactly the moment
 * to go and take that reading again.
 */
@Composable
fun SettingsSection(
    title: String,
    status: String?,
    initiallyExpanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }

    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    expanded = !expanded
                    if (expanded) onExpand?.invoke()
                }
                .padding(
                    horizontal = dimensionResource(R.dimen.space_l),
                    vertical = dimensionResource(R.dimen.space_m),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_m)),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.cd_collapse_section else R.string.cd_expand_section,
                    title,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                Column(
                    modifier = Modifier.padding(dimensionResource(R.dimen.space_l)),
                    verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_m)),
                    content = content,
                )
            }
        }
    }
}

/**
 * An inline "tell me more" toggle: a quiet label that reveals a paragraph.
 *
 * This is where the explanatory text that used to sit under every control now
 * lives. Privacy behaviour in particular is worth explaining properly — it just
 * isn't worth explaining five times, permanently, above the controls it
 * describes.
 */
@Composable
fun SettingsDisclosure(label: String, body: String) {
    var open by rememberSaveable(label) { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(
                    horizontal = dimensionResource(R.dimen.space_l),
                    vertical = dimensionResource(R.dimen.space_m),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_m)),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = dimensionResource(R.dimen.space_l),
                    end = dimensionResource(R.dimen.space_l),
                    bottom = dimensionResource(R.dimen.space_l),
                ),
            )
        }
    }
}
