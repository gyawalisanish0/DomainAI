package sg.act.domain.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import sg.act.domain.R
import sg.act.domain.data.model.Message
import sg.act.domain.data.model.Role
import sg.act.domain.data.model.Route

/** Visual descriptor for a privacy state, resolved entirely from resources. */
private data class StateVisual(val label: String, val color: Color, val icon: ImageVector)

@Composable
private fun routeVisual(route: Route): StateVisual = when (route) {
    Route.LOCAL -> StateVisual(
        stringResource(R.string.badge_local),
        colorResource(R.color.brand_local),
        Icons.Filled.Smartphone,
    )
    Route.CLOUD -> StateVisual(
        stringResource(R.string.badge_cloud),
        colorResource(R.color.brand_cloud),
        Icons.Filled.Cloud,
    )
    Route.BLOCKED -> StateVisual(
        stringResource(R.string.badge_blocked),
        colorResource(R.color.brand_blocked),
        Icons.Filled.Block,
    )
}

/** A compact pill stating where an answer came from. */
@Composable
fun RoutingBadge(route: Route, modifier: Modifier = Modifier) {
    val visual = routeVisual(route)
    val alpha = integerResource(R.integer.alpha_container_pct) / 100f
    Surface(
        color = visual.color.copy(alpha = alpha),
        shape = RoundedCornerShape(percent = integerResource(R.integer.pill_corner_percent)),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs)),
            modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.pill_pad_h),
                vertical = dimensionResource(R.dimen.pill_pad_v),
            ),
        ) {
            Icon(
                visual.icon,
                contentDescription = null,
                tint = visual.color,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_badge)),
            )
            Text(visual.label, color = visual.color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val isUser = message.role == Role.USER
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    val corner = dimensionResource(R.dimen.bubble_corner)
    val tail = dimensionResource(R.dimen.bubble_corner_tail)
    val maxWidth = dimensionResource(R.dimen.bubble_max_width)

    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = corner,
                topEnd = corner,
                bottomStart = if (isUser) corner else tail,
                bottomEnd = if (isUser) tail else corner,
            ),
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            val contentPadding = Modifier.padding(
                horizontal = dimensionResource(R.dimen.bubble_pad_h),
                vertical = dimensionResource(R.dimen.bubble_pad_v),
            )
            if (isUser) {
                Text(
                    text = message.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = contentPadding,
                )
            } else {
                AssistantContent(
                    text = message.text,
                    streaming = streaming,
                    textColor = textColor,
                    modifier = contentPadding,
                )
            }
        }
        if (!isUser) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_s)),
                modifier = Modifier.padding(top = dimensionResource(R.dimen.space_xs)),
            ) {
                RoutingBadge(route = message.route)
                if (message.text.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.action_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { clipboard.setText(AnnotatedString(message.text)) }
                            .padding(dimensionResource(R.dimen.space_xxs))
                            .size(dimensionResource(R.dimen.icon_small)),
                    )
                }
            }
            if (message.route == Route.CLOUD && message.sentPayloadPreview != null) {
                Text(
                    text = stringResource(R.string.sent_redacted, message.sentPayloadPreview),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = dimensionResource(R.dimen.space_xxs))
                        .widthIn(max = maxWidth),
                )
            }
        }
    }
}

/**
 * Renders a Domain AI reply: an optional collapsible reasoning section (for
 * models that emit a `<think>` block), then the answer. While the answer is
 * still streaming it shows as plain text (cheap — no Markdown reparse per
 * token); once complete it re-renders as selectable Markdown. If nothing has
 * arrived yet, an animated typing indicator stands in for the empty bubble.
 */
@Composable
private fun AssistantContent(
    text: String,
    streaming: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val (thinking, answer) = remember(text) { ReasoningSplit.parse(text) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs)),
    ) {
        if (thinking != null) {
            ReasoningSection(
                thinking = thinking,
                active = streaming && answer.isBlank(),
                color = textColor,
            )
        }
        when {
            // Nothing yet and no reasoning to show: animated typing indicator so
            // the bubble doesn't sit empty during prefill.
            answer.isBlank() && thinking == null && streaming ->
                TypingIndicator(
                    color = textColor.copy(alpha = TYPING_ALPHA),
                    modifier = Modifier.padding(vertical = dimensionResource(R.dimen.space_xxs)),
                )
            // Mid-stream: plain text, no Markdown reparse per token.
            streaming ->
                Text(text = answer, color = textColor, style = MaterialTheme.typography.bodyLarge)
            // Finished: full Markdown, selectable.
            answer.isNotBlank() ->
                SelectionContainer { MarkdownMessage(text = answer, textColor = textColor) }
        }
    }
}

/** Collapsible chain-of-thought section. Collapsed by default. */
@Composable
private fun ReasoningSection(thinking: String, active: Boolean, color: Color) {
    var expanded by remember { mutableStateOf(false) }
    val muted = color.copy(alpha = MUTED_ALPHA)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xxs)),
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
            )
            Text(
                text = stringResource(
                    if (active) R.string.reasoning_thinking else R.string.reasoning_label,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = muted,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(top = dimensionResource(R.dimen.space_xxs)),
                )
            }
        }
    }
}

/** Three pulsing dots shown while a reply is being generated. */
@Composable
fun TypingIndicator(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val dot = dimensionResource(R.dimen.dot_size)
    val typingLabel = stringResource(R.string.cd_typing)
    Row(
        modifier = modifier.semantics { contentDescription = typingLabel },
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(TYPING_DOTS) { index ->
            val alpha by transition.animateFloat(
                initialValue = TYPING_DOT_MIN_ALPHA,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = TYPING_DOT_MS,
                        delayMillis = index * TYPING_DOT_STAGGER_MS,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                modifier = Modifier
                    .size(dot)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }
    }
}

private const val MUTED_ALPHA = 0.7f
private const val TYPING_ALPHA = 0.6f
private const val TYPING_DOTS = 3
private const val TYPING_DOT_MIN_ALPHA = 0.25f
private const val TYPING_DOT_MS = 600
private const val TYPING_DOT_STAGGER_MS = 150

/**
 * Splits a reasoning model's reply into its `<think>…</think>` section and the
 * visible answer. Returns `null` thinking when there's no think block — the
 * common case for non-reasoning models, where the whole text is the answer.
 */
object ReasoningSplit {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun parse(text: String): Pair<String?, String> {
        val open = text.indexOf(OPEN)
        if (open < 0) return null to text
        val pre = text.substring(0, open)
        val afterOpen = open + OPEN.length
        val close = text.indexOf(CLOSE, afterOpen)
        return if (close < 0) {
            // Still inside the think block (streaming, not yet closed).
            text.substring(afterOpen).trim() to pre.trim()
        } else {
            val thinking = text.substring(afterOpen, close).trim()
            val answer = (pre + text.substring(close + CLOSE.length)).trim()
            thinking to answer
        }
    }
}

/** A labelled switch row (title + summary + Switch), shared across screens. */
@Composable
fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_l)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Context-length picker row: title/summary on the left, a dropdown of "Auto" plus
 * the device-allowed presets on the right. [chosenTokens] of 0 means Auto, and
 * [effectiveTokens] is the value Auto currently resolves to (shown in the label).
 */
@Composable
fun ContextLengthRow(
    chosenTokens: Int,
    effectiveTokens: Int,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_l)),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.setting_context_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.setting_context_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(
                    if (chosenTokens > 0) {
                        stringResource(R.string.context_tokens_label, chosenTokens)
                    } else {
                        stringResource(R.string.context_auto_label, effectiveTokens)
                    },
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.cd_context_menu),
                    modifier = Modifier.size(dimensionResource(R.dimen.icon_small)),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.context_auto_label, effectiveTokens)) },
                    onClick = { onSelect(0); expanded = false },
                )
                options.forEach { tokens ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.context_tokens_label, tokens)) },
                        onClick = { onSelect(tokens); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * A small pill shown only when the network kill switch is engaged, so the user
 * always knows the app is fully offline. When the switch is off, nothing is shown.
 */
@Composable
fun KillSwitchChip(modifier: Modifier = Modifier) {
    val color = colorResource(R.color.brand_local)
    Surface(
        color = color.copy(alpha = integerResource(R.integer.alpha_container_pct) / 100f),
        shape = RoundedCornerShape(percent = integerResource(R.integer.pill_corner_percent)),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.space_xs)),
            modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.pill_pad_h),
                vertical = dimensionResource(R.dimen.pill_pad_v),
            ),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_badge)),
            )
            Text(
                stringResource(R.string.chip_offline),
                color = color,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
