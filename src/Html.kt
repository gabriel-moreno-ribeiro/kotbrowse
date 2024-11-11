package kotbrowse

/**
 * A forgiving HTML parser: tags, attributes (quoted or not), comments,
 * doctype, entities, void elements, raw text for script/style and the usual
 * implicit end tags (`<p>` closed by blocks, `<li>` by the next `<li>`, …).
 * It always returns an `html` root with a `body`, like a real browser.
 */
object Html {
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    private val RAW = setOf("script", "style")
    val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "details", "dialog", "div", "dl", "fieldset", "figure", "footer", "form",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav", "ol", "p", "pre", "section", "table", "ul",
    )
    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "copy" to "©",
        "reg" to "®", "trade" to "™", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "laquo" to "«", "raquo" to "»", "times" to "×", "middot" to "·", "bull" to "•",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "euro" to "€",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "aacute" to "á", "agrave" to "à",
        "atilde" to "ã", "acirc" to "â", "ccedil" to "ç", "iacute" to "í", "oacute" to "ó",
        "otilde" to "õ", "ocirc" to "ô", "uacute" to "ú", "ntilde" to "ñ", "uuml" to "ü",
    )

    fun parse(html: String): Element {
        val doc = Element("#document")
        val stack = ArrayDeque<Element>()
        stack.addLast(doc)
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                stack.last().append(Text(decode(text.toString())))
                text.clear()
            }
        }
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                if (html.startsWith("<!--", i)) {
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) html.length else end + 3
                    continue
                }
                if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                    val end = html.indexOf('>', i)
                    i = if (end < 0) html.length else end + 1
                    continue
                }
                if (html.startsWith("</", i)) {
                    val end = html.indexOf('>', i)
                    if (end < 0) {
                        i = html.length
                        continue
                    }
                    flushText()
                    closeTag(stack, html.substring(i + 2, end).trim().lowercase())
                    i = end + 1
                    continue
                }
                if (i + 1 < html.length && html[i + 1].isLetter()) {
                    val (elem, next, selfClosing) = readTag(html, i)
                    flushText()
                    openTag(stack, elem)
                    i = next
                    if (elem.tag in RAW && !selfClosing) {
                        val close = html.indexOf("</${elem.tag}", i, ignoreCase = true)
                        val rawEnd = if (close < 0) html.length else close
                        if (rawEnd > i) elem.append(Text(html.substring(i, rawEnd)))
                        if (stack.last() === elem) stack.removeLast()
                        i = if (close < 0) html.length else html.indexOf('>', close).let { if (it < 0) html.length else it + 1 }
                    }
                    continue
                }
            }
            text.append(c)
            i++
        }
        flushText()
        return finish(doc)
    }

    private fun readTag(html: String, start: Int): Triple<Element, Int, Boolean> {
        var i = start + 1
        val nameStart = i
        while (i < html.length && !html[i].isWhitespace() && html[i] != '>' && html[i] != '/') i++
        val name = html.substring(nameStart, i).lowercase()
        val attrs = LinkedHashMap<String, String>()
        var selfClosing = false
        while (i < html.length) {
            while (i < html.length && html[i].isWhitespace()) i++
            if (i >= html.length) break
            if (html[i] == '>') {
                i++
                break
            }
            if (html[i] == '/') {
                selfClosing = true
                i++
                continue
            }
            val attrStart = i
            while (i < html.length && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' && html[i] != '/') i++
            val attrName = html.substring(attrStart, i).lowercase()
            while (i < html.length && html[i].isWhitespace()) i++
            var value = ""
            if (i < html.length && html[i] == '=') {
                i++
                while (i < html.length && html[i].isWhitespace()) i++
                if (i < html.length && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i]
                    val end = html.indexOf(quote, i + 1)
                    value = if (end < 0) html.substring(i + 1) else html.substring(i + 1, end)
                    i = if (end < 0) html.length else end + 1
                } else {
                    val valueStart = i
                    while (i < html.length && !html[i].isWhitespace() && html[i] != '>') i++
                    value = html.substring(valueStart, i)
                }
            }
            if (attrName.isNotEmpty() && attrName !in attrs) attrs[attrName] = decode(value)
        }
        return Triple(Element(name, attrs), i, selfClosing)
    }

    private fun openTag(stack: ArrayDeque<Element>, elem: Element) {
        when (val tag = elem.tag) {
            in BLOCKS -> closeIfOpenWithin(stack, "p", BLOCKS - "p")
            "li" -> closeIfOpenWithin(stack, "li", setOf("ul", "ol", "menu"))
            "dt", "dd" -> {
                closeIfOpenWithin(stack, "dt", setOf("dl"))
                closeIfOpenWithin(stack, "dd", setOf("dl"))
            }
            "td", "th" -> {
                closeIfOpenWithin(stack, "td", setOf("tr", "table"))
                closeIfOpenWithin(stack, "th", setOf("tr", "table"))
            }
            "tr" -> closeIfOpenWithin(stack, "tr", setOf("table", "tbody", "thead", "tfoot"))
            "option" -> closeIfOpenWithin(stack, "option", setOf("select"))
            else -> if (tag == "body" || tag == "head" || tag == "html") {
                // duplicated structural tags are ignored (their content still lands in the document)
                val existing = stack.firstOrNull { it.tag == tag }
                if (existing != null) return
            }
        }
        stack.last().append(elem)
        if (elem.tag !in VOID) stack.addLast(elem)
    }

    /** Closes `name` if it is open above the nearest `boundaries` element. */
    private fun closeIfOpenWithin(stack: ArrayDeque<Element>, name: String, boundaries: Set<String>) {
        for (k in stack.indices.reversed()) {
            val t = stack[k].tag
            if (t == name) {
                while (stack.size > k) stack.removeLast()
                return
            }
            if (t in boundaries) return
        }
    }

    private fun closeTag(stack: ArrayDeque<Element>, name: String) {
        val idx = stack.indexOfLast { it.tag == name }
        if (idx <= 0) return
        while (stack.size > idx) stack.removeLast()
    }

    private fun finish(doc: Element): Element {
        val html = doc.children.filterIsInstance<Element>().firstOrNull { it.tag == "html" }
            ?: Element("html").also { root -> doc.children.forEach { root.append(it) } }
        html.parent = null
        val hasBody = html.children.any { it is Element && it.tag == "body" }
        if (!hasBody) {
            val body = Element("body")
            val moved = html.children.filter { !(it is Element && it.tag == "head") }
            html.children.removeAll(moved)
            moved.forEach { body.append(it) }
            html.append(body)
        }
        return html
    }

    /** Replaces character references such as `&amp;`, `&#65;` and `&#x41;`. */
    fun decode(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '&') {
                val semi = s.indexOf(';', i)
                if (semi in i + 2..i + 10) {
                    val name = s.substring(i + 1, semi)
                    val rep = when {
                        name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)?.let { codePoint(it) }
                        name.startsWith("#") -> name.substring(1).toIntOrNull()?.let { codePoint(it) }
                        else -> ENTITIES[name]
                    }
                    if (rep != null) {
                        sb.append(rep)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String? = if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null
}
