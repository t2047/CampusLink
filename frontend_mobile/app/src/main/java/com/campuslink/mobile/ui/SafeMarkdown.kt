package com.campuslink.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownTextNode
import org.commonmark.parser.Parser

@Composable
fun SafeMarkdownText(value: String) {
    val annotated = markdownToAnnotated(value, MaterialTheme.colorScheme.primary)
    Text(annotated, style = MaterialTheme.typography.bodyLarge)
}

internal fun markdownToAnnotated(value: String, linkColor: Color): AnnotatedString {
    val document = Parser.builder().build().parse(value)
    return buildAnnotatedString {
        document.accept(object : AbstractVisitor() {
            override fun visit(text: MarkdownTextNode) = append(text.literal)
            override fun visit(softLineBreak: SoftLineBreak) {
                append('\n')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                append('\n')
            }

            override fun visit(paragraph: Paragraph) {
                visitChildren(paragraph)
                if (length > 0 && !this@buildAnnotatedString.toString().endsWith("\n\n")) append("\n\n")
            }

            override fun visit(heading: Heading) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                visitChildren(heading)
                pop()
                append("\n")
            }

            override fun visit(emphasis: Emphasis) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                visitChildren(emphasis)
                pop()
            }

            override fun visit(strongEmphasis: StrongEmphasis) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                visitChildren(strongEmphasis)
                pop()
            }

            override fun visit(code: Code) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(code.literal)
                pop()
            }

            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                append(fencedCodeBlock.literal)
                pop()
                append('\n')
            }

            override fun visit(bulletList: BulletList) = visitChildren(bulletList)
            override fun visit(listItem: ListItem) {
                append("• ")
                visitChildren(listItem)
                if (!this@buildAnnotatedString.toString().endsWith("\n")) append('\n')
            }

            override fun visit(link: Link) {
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                visitChildren(link)
                pop()
                if (link.destination.isNotBlank()) append(" (${link.destination})")
            }

            // 原始 HTML 不进入 Android UI，避免脚本、跟踪像素和不受控布局。
            override fun visit(htmlInline: HtmlInline) = Unit
            override fun visit(htmlBlock: HtmlBlock) = Unit
        })
    }.let { result ->
        if (result.text.endsWith("\n\n")) result.subSequence(0, result.length - 2) else result
    }
}
