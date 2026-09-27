package com.sleepysoong.hoard.tools.fetch

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayInputStream

/**
 * HTML → the page's main readable content as compact Markdown.
 *
 * 1. Drop non-content: script/style/forms/iframes/svg, nav/footer/aside,
 *    ARIA landmarks for navigation/banners, hidden nodes, and blocks whose
 *    class/id look like ads, cookie banners, share bars, comments, sidebars…
 * 2. Pick the content root: `<article>`/`<main>`/`[role=main]`, else the block
 *    with the most paragraph text (a small Readability-style score), else body.
 * 3. Render headings, paragraphs, lists, tables, quotes, code, links
 *    (absolute URLs) and emphasis as Markdown; images are left out.
 */
object HtmlExtractor {
    data class Extracted(val title: String, val content: String)

    fun extract(html: ByteArray, charset: String?, baseUrl: String): Extracted {
        // charset null → jsoup sniffs BOM / <meta charset>, defaulting to UTF-8.
        val doc = Jsoup.parse(ByteArrayInputStream(html), charset?.takeIf { runCatching { charset(it) }.isSuccess }, baseUrl)
        return extract(doc)
    }

    fun extract(html: String, baseUrl: String): Extracted = extract(Jsoup.parse(html, baseUrl))

    private fun extract(doc: Document): Extracted {
        val title = title(doc)
        clean(doc)
        val root = contentRoot(doc)
        val md = Markdown().apply { render(root) }.result()
        return Extracted(title, md)
    }

    private fun title(doc: Document): String {
        val og = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val t = doc.title().trim()
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        return t.ifEmpty { og }.ifEmpty { h1 }.take(300)
    }

    private val REMOVE_TAGS = "script, style, noscript, template, svg, canvas, iframe, frame, object, embed, form, button, " +
        "input, select, textarea, nav, footer, aside, dialog, menu, link, meta, img, picture, video, audio, source, map"
    private val REMOVE_ATTR = "[hidden], [aria-hidden=true], [role=navigation], [role=banner], [role=contentinfo], " +
        "[role=complementary], [role=search], [role=dialog], [role=alertdialog], [style*=display:none], [style*=display: none]"
    private val JUNK = Regex(
        "(^|[\\s_-])(ad|ads|adsbygoogle|advert|advertisement|banner|sponsor(ed)?|promo|cookie|consent|gdpr|popup|modal|" +
            "newsletter|subscribe|share|sharing|social|related|recommend(ed|ations)?|comments?|sidebar|breadcrumbs?|" +
            "pagination|footer|navbar|nav|menu|toolbar|skip-link|outbrain|taboola)($|[\\s_-])",
        RegexOption.IGNORE_CASE
    )

    private fun clean(doc: Document) {
        doc.select(REMOVE_TAGS).remove()
        doc.select(REMOVE_ATTR).remove()
        // Page-level <header> is site chrome; one inside <article> usually holds the headline.
        doc.select("header").filter { it.parents().none { p -> p.tagName() == "article" || p.tagName() == "main" } }.forEach { it.remove() }
        val bodyText = doc.body().text().length
        doc.select("[class], [id]").toList().forEach { e ->
            if (e.tagName() in setOf("html", "body", "main", "article")) return@forEach
            val tag = (e.className() + " " + e.id())
            // Never drop something holding most of the page (a mislabelled wrapper).
            if (JUNK.containsMatchIn(tag) && e.parent() != null && e.text().length < bodyText * 0.5) e.remove()
        }
    }

    private fun contentRoot(doc: Document): Element {
        val body = doc.body()
        doc.select("article").maxByOrNull { it.text().length }?.takeIf { it.text().length > 200 }?.let { return it }
        (doc.selectFirst("main") ?: doc.selectFirst("[role=main]"))?.takeIf { it.text().length > 200 }?.let { return it }
        // Readability-lite: paragraphs vote for their parent (full) and grandparent (half).
        val score = HashMap<Element, Double>()
        for (p in body.select("p, pre, li")) {
            val len = p.text().length
            if (len < 25) continue
            val linkText = p.select("a").sumOf { it.text().length }
            val value = len * (1.0 - linkText.toDouble() / len)
            p.parent()?.let { score[it] = (score[it] ?: 0.0) + value }
            p.parent()?.parent()?.let { score[it] = (score[it] ?: 0.0) + value / 2 }
        }
        return score.maxByOrNull { it.value }?.takeIf { it.value > 200 }?.key ?: body
    }

    private const val BLOCKS = "p, table, ul, ol, pre, blockquote, h1, h2, h3, h4, h5, h6, dl, hr"

    /** Block/inline Markdown writer with blank-line separated blocks. */
    private class Markdown {
        private val out = StringBuilder()

        fun result(): String = out.toString()
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        private fun para(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(t)
        }

        fun render(e: Element) {
            block(e)
            flush()
        }

        private fun block(e: Element) {
            for (child in e.childNodes()) blockNode(child)
        }

        private val inlineBuffer = StringBuilder()

        private fun flush() {
            if (inlineBuffer.isNotBlank()) para(inlineBuffer.toString())
            inlineBuffer.setLength(0)
        }

        private fun blockNode(n: Node) {
            when (n) {
                is TextNode -> inlineBuffer.append(collapse(n.text()))
                is Element -> when (val tag = n.tagName()) {
                    "h1", "h2", "h3", "h4", "h5", "h6" -> { flush(); para("#".repeat(tag[1].digitToInt()) + " " + inline(n)) }
                    "p" -> { flush(); para(inline(n)) }
                    "br" -> inlineBuffer.append("\n")
                    "hr" -> { flush(); para("---") }
                    "ul", "ol" -> { flush(); para(list(n, 0)) }
                    "pre" -> { flush(); para("```\n" + n.wholeText().trimEnd() + "\n```") }
                    "blockquote" -> {
                        flush()
                        val inner = Markdown().apply { render(n) }.result()
                        para(inner.lines().joinToString("\n") { "> $it" })
                    }
                    "table" -> { flush(); para(table(n)) }
                    "dl" -> {
                        flush()
                        n.children().forEach { c ->
                            when (c.tagName()) {
                                "dt" -> para("**" + inline(c) + "**")
                                "dd" -> para(inline(c))
                            }
                        }
                    }
                    "div", "section", "article", "main", "header", "figure", "figcaption", "details", "summary", "center", "body" -> {
                        flush(); block(n); flush()
                    }
                    // Unknown/custom wrappers (e.g. GitHub's <markdown-accessiblity-table>) holding
                    // blocks are containers, not inline text.
                    else -> if (n.selectFirst(BLOCKS) != null) { flush(); block(n); flush() } else inlineBuffer.append(inline(n))
                }
            }
        }

        private fun list(e: Element, depth: Int): String {
            val ordered = e.tagName() == "ol"
            val sb = StringBuilder()
            var i = 1
            for (li in e.children()) {
                if (li.tagName() != "li") continue
                val nested = li.children().filter { it.tagName() == "ul" || it.tagName() == "ol" }
                nested.forEach { it.remove() }
                val text = inline(li).trim()
                if (text.isNotEmpty()) {
                    sb.append("  ".repeat(depth)).append(if (ordered) "${i++}. " else "- ").append(text).append('\n')
                }
                nested.forEach { sb.append(list(it, depth + 1)).append('\n') }
            }
            return sb.toString().trimEnd()
        }

        private fun table(t: Element): String {
            val rows = t.select("tr").map { tr -> tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { inline(it).replace("|", "\\|").trim() } }
                .filter { r -> r.any { it.isNotEmpty() } }
            if (rows.isEmpty()) return ""
            val cols = rows.maxOf { it.size }.coerceAtMost(12)
            val norm = rows.map { r -> (0 until cols).map { r.getOrElse(it) { "" } } }
            val sb = StringBuilder()
            sb.append("| ").append(norm[0].joinToString(" | ")).append(" |\n")
            sb.append("|").append(" --- |".repeat(cols)).append('\n')
            norm.drop(1).forEach { sb.append("| ").append(it.joinToString(" | ")).append(" |\n") }
            return sb.toString().trimEnd()
        }

        /** Inline content of [e] as one Markdown line (block children flattened). */
        private fun inline(e: Element): String {
            val sb = StringBuilder()
            for (n in e.childNodes()) inlineNode(n, sb)
            return sb.toString().replace(Regex("[ \\t]+"), " ").replace(Regex(" *\n *"), "\n").trim()
        }

        private fun inlineNode(n: Node, sb: StringBuilder) {
            when (n) {
                is TextNode -> sb.append(collapse(n.text()))
                is Element -> when (n.tagName()) {
                    "br" -> sb.append("\n")
                    "strong", "b" -> wrap(sb, "**", inline(n))
                    "em", "i" -> wrap(sb, "*", inline(n))
                    "code", "kbd", "samp" -> n.text().takeIf { it.isNotBlank() }?.let { sb.append('`').append(it.replace("`", "'")).append('`') }
                    "a" -> {
                        val text = inline(n)
                        val href = n.absUrl("href")
                        if (text.isBlank()) return
                        if (href.startsWith("http://") || href.startsWith("https://")) sb.append('[').append(text).append("](").append(href).append(')')
                        else sb.append(text)
                    }
                    "sup" -> sb.append("^").append(inline(n))
                    else -> { sb.append(' '); for (c in n.childNodes()) inlineNode(c, sb); if (isBlockish(n)) sb.append(' ') }
                }
            }
        }

        private fun isBlockish(e: Element) = e.tagName() in setOf("div", "p", "li", "td", "th", "section", "span")

        private fun wrap(sb: StringBuilder, mark: String, text: String) {
            if (text.isBlank()) return
            sb.append(mark).append(text.trim()).append(mark)
        }

        private fun collapse(s: String) = s.replace(Regex("\\s+"), " ")
    }
}
