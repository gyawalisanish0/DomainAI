package sg.act.domain.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes

/**
 * Renders an Domain AI reply as Markdown — headings, lists, tables, links,
 * blockquotes, inline code, and syntax-highlighted fenced code blocks. Pure
 * Compose (no WebView), so nothing about rendering touches the network.
 *
 * Re-parses on each recomposition, so it formats live as a reply streams in.
 */
@Composable
fun MarkdownMessage(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val highlightsBuilder = remember(dark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = dark))
    }
    Markdown(
        content = text,
        colors = markdownColor(text = textColor),
        modifier = modifier,
        components = markdownComponents(
            codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, highlightsBuilder) },
            codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, highlightsBuilder) },
        ),
    )
}
