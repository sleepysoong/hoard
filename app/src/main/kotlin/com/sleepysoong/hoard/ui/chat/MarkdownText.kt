package com.sleepysoong.hoard.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.hrm.markdown.renderer.Markdown
import com.hrm.markdown.renderer.MarkdownConfig
import com.hrm.markdown.renderer.MarkdownImageRenderer
import com.hrm.markdown.renderer.MarkdownTheme
import com.hrm.codehigh.theme.GithubLightTheme
import com.hrm.codehigh.theme.OneDarkProTheme
import com.hrm.latex.renderer.model.LatexTheme
import com.sleepysoong.hoard.ui.theme.LocalHoardDarkTheme

/**
 * A model reply rendered as Markdown: CommonMark + GFM (tables, strikethrough,
 * task lists), code blocks, and LaTeX math (`$…$` inline, `$$…$$` block).
 *
 * - Static layout (no inner LazyColumn/scroll) so it nests inside the chat list.
 * - Selection is off so the bubble's long-press menu keeps working.
 * - Colors follow the app's matcha/cocoa scheme; body text uses the bubble style.
 * - Remote images are NOT fetched (a reply could embed tracking pixels); the alt
 *   text is shown instead.
 */
@Composable
fun MarkdownText(
    markdown: String,
    color: Color,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge
    val dark = LocalHoardDarkTheme.current
    val theme = remember(scheme, body, color, dark) {
        val base = MarkdownTheme.material3(scheme)
        base.copy(
            bodyStyle = base.bodyStyle.merge(body).copy(color = color),
            headingStyles = base.headingStyles.map { it.copy(color = color, fontFamily = body.fontFamily) },
            // Math sits directly on the bubble: no box behind formulas.
            latexTheme = if (dark) LatexTheme.dark(color = color, backgroundColor = Color.Transparent)
            else LatexTheme.light(color = color, backgroundColor = Color.Transparent),
            listBulletColor = color,
            // Translucent insets read on both the glass bubble and dark mode.
            codeBlockBackground = scheme.onSurface.copy(alpha = 0.06f),
            inlineCodeBackground = scheme.onSurface.copy(alpha = 0.08f),
            mathBlockBackground = Color.Transparent,
            tableHeaderBackground = scheme.onSurface.copy(alpha = 0.05f),
            blockSpacing = 8.dp,
            listIndent = 20.dp
        )
    }
    val uriHandler = LocalUriHandler.current
    Markdown(
        markdown = markdown,
        modifier = modifier,
        theme = theme,
        codeTheme = if (dark) OneDarkProTheme else GithubLightTheme,
        // No append coalescing (LlmStreaming holds back <16 chars): a short
        // streamed reply must show as soon as it arrives.
        config = MarkdownConfig.Default,
        isStreaming = isStreaming,
        enableScroll = false,
        enableSelection = false,
        imageContent = NoRemoteImages,
        onLinkClick = { url -> runCatching { uriHandler.openUri(url) } }
    )
}

private val NoRemoteImages: MarkdownImageRenderer = { data, modifier ->
    Text(
        "🖼 " + data.altText.ifBlank { data.url },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 2.dp)
    )
}
