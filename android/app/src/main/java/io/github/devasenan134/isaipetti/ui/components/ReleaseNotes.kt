package io.github.devasenan134.isaipetti.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * Release notes are written in Markdown (see CHANGELOG.md). This shows the parts they use:
 * headings, **bold**, bullet points, `code` and [links](url), without the raw symbols.
 */
fun releaseNotesText(markdown: String): AnnotatedString = buildAnnotatedString {
    val lines = markdown.lines().map { it.trimEnd() }.filter { it != "---" }
    lines.forEachIndexed { i, raw ->
        val line = raw.trimStart()
        when {
            line.startsWith("#") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(line.trimStart('#').trim()) }
            line.startsWith("- ") || line.startsWith("* ") -> { append("•  "); inline(line.drop(2)) }
            else -> inline(line)
        }
        if (i < lines.lastIndex) append('\n')
    }
}

/** **bold**, `code`, _italic_ and [text](url), shown as plain styled text. */
private fun AnnotatedString.Builder.inline(text: String) {
    val cleaned = text
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace("`", "")
        .replace(Regex("""(^|\s)_([^_]+)_(\s|$|[.,])"""), "$1$2$3")
    val parts = cleaned.split("**")
    parts.forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part) }
}

/** Text cut to a few lines, with "Show more" / "Show less" when it's longer. */
@Composable
fun ExpandableText(text: AnnotatedString, collapsedLines: Int = 6, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
        }
    }
}
