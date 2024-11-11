package kotbrowse

/** An sRGB colour with alpha. */
data class Color(val r: Int, val g: Int, val b: Int, val a: Int = 255) {
    val visible: Boolean get() = a > 0

    companion object {
        val BLACK = Color(0, 0, 0)
        val WHITE = Color(255, 255, 255)
        val TRANSPARENT = Color(0, 0, 0, 0)

        private val NAMED = mapOf(
            "black" to 0x000000, "white" to 0xffffff, "red" to 0xff0000, "green" to 0x008000, "blue" to 0x0000ff,
            "yellow" to 0xffff00, "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xc0c0c0, "maroon" to 0x800000,
            "purple" to 0x800080, "fuchsia" to 0xff00ff, "lime" to 0x00ff00, "olive" to 0x808000, "navy" to 0x000080,
            "teal" to 0x008080, "aqua" to 0x00ffff, "cyan" to 0x00ffff, "magenta" to 0xff00ff, "orange" to 0xffa500,
            "pink" to 0xffc0cb, "brown" to 0xa52a2a, "gold" to 0xffd700, "coral" to 0xff7f50, "crimson" to 0xdc143c,
            "darkblue" to 0x00008b, "darkgreen" to 0x006400, "darkgray" to 0xa9a9a9, "darkgrey" to 0xa9a9a9,
            "darkred" to 0x8b0000, "darkorange" to 0xff8c00, "lightgray" to 0xd3d3d3, "lightgrey" to 0xd3d3d3,
            "lightblue" to 0xadd8e6, "lightgreen" to 0x90ee90, "lightyellow" to 0xffffe0, "whitesmoke" to 0xf5f5f5,
            "beige" to 0xf5f5dc, "ivory" to 0xfffff0, "khaki" to 0xf0e68c, "indigo" to 0x4b0082, "violet" to 0xee82ee,
            "tomato" to 0xff6347, "salmon" to 0xfa8072, "steelblue" to 0x4682b4, "royalblue" to 0x4169e1,
            "dodgerblue" to 0x1e90ff, "skyblue" to 0x87ceeb, "slategray" to 0x708090, "slategrey" to 0x708090,
            "dimgray" to 0x696969, "dimgrey" to 0x696969, "gainsboro" to 0xdcdcdc, "linen" to 0xfaf0e6, "tan" to 0xd2b48c,
            "turquoise" to 0x40e0d0, "orchid" to 0xda70d6, "plum" to 0xdda0dd, "seagreen" to 0x2e8b57,
            "forestgreen" to 0x228b22, "firebrick" to 0xb22222, "chocolate" to 0xd2691e, "wheat" to 0xf5deb3,
            "lavender" to 0xe6e6fa, "mintcream" to 0xf5fffa, "aliceblue" to 0xf0f8ff, "honeydew" to 0xf0fff0,
            "azure" to 0xf0ffff, "snow" to 0xfffafa, "rebeccapurple" to 0x663399, "midnightblue" to 0x191970,
            "cornflowerblue" to 0x6495ed, "lightcoral" to 0xf08080, "darkslategray" to 0x2f4f4f, "goldenrod" to 0xdaa520,
        )

        fun parse(text: String?): Color? {
            val v = text?.trim()?.lowercase() ?: return null
            if (v == "transparent") return TRANSPARENT
            NAMED[v]?.let { return Color(it shr 16 and 255, it shr 8 and 255, it and 255) }
            if (v.startsWith("#")) {
                val h = v.substring(1)
                if (h.isEmpty() || !h.all { it.isDigit() || it in 'a'..'f' }) return null
                fun d(k: Int) = h[k].digitToInt(16) * 17
                fun p(k: Int) = h.substring(k, k + 2).toInt(16)
                return when (h.length) {
                    3 -> Color(d(0), d(1), d(2))
                    4 -> Color(d(0), d(1), d(2), d(3))
                    6 -> Color(p(0), p(2), p(4))
                    8 -> Color(p(0), p(2), p(4), p(6))
                    else -> null
                }
            }
            if (v.startsWith("rgb")) {
                val inner = v.substringAfter('(', "").substringBefore(')')
                val parts = inner.split(',', ' ', '/').filter { it.isNotBlank() }
                if (parts.size < 3) return null
                fun channel(p: String): Int? {
                    val n = if (p.endsWith("%")) p.dropLast(1).toDoubleOrNull()?.times(2.55) else p.toDoubleOrNull()
                    return n?.let { Math.round(it).toInt().coerceIn(0, 255) }
                }
                val r = channel(parts[0]) ?: return null
                val g = channel(parts[1]) ?: return null
                val b = channel(parts[2]) ?: return null
                val a = if (parts.size > 3) {
                    val p = parts[3]
                    val f = if (p.endsWith("%")) p.dropLast(1).toDoubleOrNull()?.div(100) else p.toDoubleOrNull()
                    Math.round((f ?: 1.0) * 255).toInt().coerceIn(0, 255)
                } else 255
                return Color(r, g, b, a)
            }
            return null
        }
    }
}

/** A CSS length. Relative units are resolved during layout. */
sealed class Length {
    data class Px(val v: Double) : Length()
    data class Em(val v: Double) : Length()
    data class Percent(val v: Double) : Length()
    object Auto : Length() {
        override fun toString() = "auto"
    }

    /** Resolves against the element's font size and the containing block dimension. */
    fun resolve(fontSize: Double, base: Double): Double = when (this) {
        is Px -> v
        is Em -> v * fontSize
        is Percent -> v / 100.0 * base
        Auto -> 0.0
    }

    companion object {
        fun parse(text: String?): Length? {
            val v = text?.trim()?.lowercase() ?: return null
            if (v == "auto") return Auto
            val num = v.takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
            val n = num.toDoubleOrNull() ?: return null
            return when (v.substring(num.length)) {
                "px", "" -> Px(n)
                "em" -> Em(n)
                "rem" -> Px(n * 16)
                "%", "vw" -> Percent(n)
                "pt" -> Px(n * 4 / 3)
                "pc" -> Px(n * 16)
                "in" -> Px(n * 96)
                "cm" -> Px(n * 37.8)
                "mm" -> Px(n * 3.78)
                "ex", "ch" -> Em(n * 0.5)
                else -> null
            }
        }
    }
}

data class Specificity(val ids: Int, val classes: Int, val tags: Int) : Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int = compareValuesBy(this, other, { it.ids }, { it.classes }, { it.tags })
    operator fun plus(o: Specificity) = Specificity(ids + o.ids, classes + o.classes, tags + o.tags)
}

/** One `tag#id.class[attr=value]` group of a selector. */
class Compound(val tag: String?, val id: String?, val classes: List<String>, val attrs: List<Pair<String, String?>>) {
    fun matches(e: Element): Boolean {
        if (tag != null && tag != "*" && tag != e.tag) return false
        if (id != null && e.id != id) return false
        if (classes.isNotEmpty()) {
            val have = e.classes
            if (!classes.all { it in have }) return false
        }
        for ((name, value) in attrs) {
            val actual = e.attrs[name] ?: return false
            if (value != null && actual != value) return false
        }
        return true
    }

    val specificity: Specificity
        get() = Specificity(if (id != null) 1 else 0, classes.size + attrs.size, if (tag != null && tag != "*") 1 else 0)
}

/** A complex selector: compounds joined by descendant (' ') or child ('>') combinators. */
class Selector(val parts: List<Pair<Compound, Char>>, val unsupported: Boolean = false) {
    val specificity: Specificity = parts.fold(Specificity(0, 0, 0)) { acc, p -> acc + p.first.specificity }

    fun matches(e: Element): Boolean = !unsupported && parts.isNotEmpty() && matchFrom(parts.lastIndex, e)

    private fun matchFrom(i: Int, el: Element): Boolean {
        if (!parts[i].first.matches(el)) return false
        if (i == 0) return true
        return when (parts[i].second) {
            '>' -> el.parent?.let { matchFrom(i - 1, it) } ?: false
            else -> generateSequence(el.parent) { it.parent }.any { matchFrom(i - 1, it) }
        }
    }

    companion object {
        fun parse(text: String): Selector {
            val s = text.trim()
            if (s.isEmpty()) return Selector(emptyList(), true)
            val parts = mutableListOf<Pair<Compound, Char>>()
            var i = 0
            var combinator = ' '
            var unsupported = false
            fun ident(): String {
                val st = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '-' || s[i] == '_')) i++
                return s.substring(st, i)
            }
            while (i < s.length) {
                var sawSpace = false
                while (i < s.length && s[i].isWhitespace()) {
                    i++
                    sawSpace = true
                }
                if (i >= s.length) break
                if (s[i] == '>' || s[i] == '+' || s[i] == '~') {
                    if (s[i] != '>') unsupported = true
                    combinator = '>'
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                } else if (sawSpace) combinator = ' '
                var tag: String? = null
                var id: String? = null
                val classes = mutableListOf<String>()
                val attrs = mutableListOf<Pair<String, String?>>()
                if (i < s.length && (s[i].isLetter() || s[i] == '*')) {
                    tag = if (s[i] == '*') {
                        i++
                        "*"
                    } else ident().lowercase()
                }
                while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '+' && s[i] != '~') {
                    when (s[i]) {
                        '#' -> {
                            i++
                            id = ident()
                        }
                        '.' -> {
                            i++
                            classes.add(ident())
                        }
                        '[' -> {
                            val end = s.indexOf(']', i)
                            if (end < 0) {
                                unsupported = true
                                i = s.length
                            } else {
                                val body = s.substring(i + 1, end)
                                val eq = body.indexOf('=')
                                if (eq < 0) attrs.add(body.trim().lowercase() to null)
                                else {
                                    val name = body.substring(0, eq).trimEnd('~', '|', '^', '$', '*').trim().lowercase()
                                    val value = body.substring(eq + 1).trim().trim('"', '\'')
                                    if (body[eq - 1] in "~|^$*") unsupported = true
                                    attrs.add(name to value)
                                }
                                i = end + 1
                            }
                        }
                        else -> {
                            // pseudo-classes, pseudo-elements and anything else we do not implement
                            unsupported = true
                            i++
                            var depth = 0
                            while (i < s.length && (depth > 0 || (!s[i].isWhitespace() && s[i] != '>' && s[i] != ','))) {
                                if (s[i] == '(') depth++ else if (s[i] == ')') depth--
                                i++
                            }
                        }
                    }
                }
                if (tag == null && id == null && classes.isEmpty() && attrs.isEmpty()) unsupported = true
                parts.add(Compound(tag, id, classes, attrs) to combinator)
                combinator = ' '
            }
            return Selector(parts, unsupported)
        }
    }
}

data class Declaration(val name: String, val value: String, val important: Boolean)

class Rule(val selectors: List<Selector>, val declarations: List<Declaration>)

class Stylesheet(val rules: List<Rule>) {
    companion object {
        fun parse(css: String): Stylesheet {
            val s = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            val rules = mutableListOf<Rule>()
            parseRules(s, rules)
            return Stylesheet(rules)
        }

        private fun parseRules(s: String, out: MutableList<Rule>) {
            var i = 0
            while (i < s.length) {
                while (i < s.length && (s[i].isWhitespace() || s[i] == '}')) i++
                if (i >= s.length) break
                if (s[i] == '@') {
                    val brace = s.indexOf('{', i)
                    val semi = s.indexOf(';', i)
                    if (semi >= 0 && (brace < 0 || semi < brace)) {
                        i = semi + 1 // @import, @charset
                        continue
                    }
                    if (brace < 0) break
                    val prelude = s.substring(i + 1, brace).trim()
                    val end = matchBrace(s, brace)
                    if (prelude.startsWith("media") && mediaApplies(prelude.removePrefix("media"))) {
                        parseRules(s.substring(brace + 1, end), out)
                    }
                    i = end + 1
                    continue
                }
                val brace = s.indexOf('{', i)
                if (brace < 0) break
                val selectorText = s.substring(i, brace)
                val end = matchBrace(s, brace)
                val selectors = splitTopLevel(selectorText, ',').map { Selector.parse(it) }
                val decls = parseDeclarations(s.substring(brace + 1, end))
                if (selectors.any { !it.unsupported }) out.add(Rule(selectors, decls))
                i = end + 1
            }
        }

        private fun matchBrace(s: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < s.length) {
                if (s[i] == '{') depth++
                else if (s[i] == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return s.length
        }

        /** Only simple screen queries are honoured; width-dependent and print rules are skipped. */
        private fun mediaApplies(query: String): Boolean {
            val q = query.lowercase()
            return !q.contains("print") && !q.contains("width") && !q.contains("prefers") && !q.contains("orientation")
        }

        fun splitTopLevel(s: String, sep: Char): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var quote: Char? = null
            val cur = StringBuilder()
            for (c in s) {
                if (quote != null) {
                    cur.append(c)
                    if (c == quote) quote = null
                    continue
                }
                when {
                    c == '"' || c == '\'' -> {
                        quote = c
                        cur.append(c)
                    }
                    c == '(' -> {
                        depth++
                        cur.append(c)
                    }
                    c == ')' -> {
                        depth--
                        cur.append(c)
                    }
                    c == sep && depth <= 0 -> {
                        out.add(cur.toString())
                        cur.clear()
                    }
                    else -> cur.append(c)
                }
            }
            out.add(cur.toString())
            return out
        }

        fun parseDeclarations(body: String): List<Declaration> {
            val out = mutableListOf<Declaration>()
            for (chunk in splitTopLevel(body, ';')) {
                val colon = chunk.indexOf(':')
                if (colon < 0) continue
                val name = chunk.substring(0, colon).trim().lowercase()
                var value = chunk.substring(colon + 1).trim()
                val important = value.endsWith("!important", ignoreCase = true)
                if (important) value = value.dropLast(10).trim()
                if (name.isEmpty() || value.isEmpty()) continue
                for ((n, v) in expand(name, value)) out.add(Declaration(n, v, important))
            }
            return out
        }

        private val SIDES = listOf("top", "right", "bottom", "left")
        private val BORDER_STYLES = setOf("none", "hidden", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset")

        private fun tokens(value: String) = splitTopLevel(value, ' ').filter { it.isNotBlank() }

        private fun fourSides(value: String): List<String>? {
            val p = tokens(value)
            return when (p.size) {
                1 -> listOf(p[0], p[0], p[0], p[0])
                2 -> listOf(p[0], p[1], p[0], p[1])
                3 -> listOf(p[0], p[1], p[2], p[1])
                4 -> p
                else -> null
            }
        }

        private fun borderParts(value: String): List<Pair<String, String>> = tokens(value).map { tok ->
            val t = tok.lowercase()
            when {
                t in BORDER_STYLES -> "style" to t
                t in setOf("thin", "medium", "thick") || Length.parse(t) != null -> "width" to t
                else -> "color" to tok
            }
        }

        /** Expands shorthand properties into the longhands the style engine understands. */
        fun expand(name: String, value: String): List<Pair<String, String>> = when (name) {
            "margin", "padding" -> fourSides(value)?.let { v -> SIDES.mapIndexed { k, side -> "$name-$side" to v[k] } } ?: emptyList()
            "border-width", "border-style", "border-color" -> {
                val what = name.removePrefix("border-")
                fourSides(value)?.let { v -> SIDES.mapIndexed { k, side -> "border-$side-$what" to v[k] } } ?: emptyList()
            }
            "border" -> {
                val parts = borderParts(value)
                val width = parts.firstOrNull { it.first == "width" }?.second ?: "medium"
                val style = parts.firstOrNull { it.first == "style" }?.second ?: "none"
                val color = parts.firstOrNull { it.first == "color" }?.second ?: "currentcolor"
                SIDES.flatMap { side -> listOf("border-$side-width" to width, "border-$side-style" to style, "border-$side-color" to color) }
            }
            "border-top", "border-right", "border-bottom", "border-left" -> {
                val parts = borderParts(value)
                listOf(
                    "$name-width" to (parts.firstOrNull { it.first == "width" }?.second ?: "medium"),
                    "$name-style" to (parts.firstOrNull { it.first == "style" }?.second ?: "none"),
                    "$name-color" to (parts.firstOrNull { it.first == "color" }?.second ?: "currentcolor"),
                )
            }
            "background" -> {
                val color = tokens(value).firstOrNull { Color.parse(it) != null }
                when {
                    color != null -> listOf("background-color" to color)
                    value.trim().equals("none", ignoreCase = true) -> listOf("background-color" to "transparent")
                    else -> emptyList()
                }
            }
            "font" -> expandFont(value)
            "list-style" -> tokens(value).filter { it.lowercase() in setOf("none", "disc", "circle", "square", "decimal") }.map { "list-style-type" to it.lowercase() }
            "text-decoration-line" -> listOf("text-decoration" to value)
            else -> listOf(name to value)
        }

        private fun expandFont(value: String): List<Pair<String, String>> {
            val toks = tokens(value)
            val out = mutableListOf<Pair<String, String>>()
            var idx = 0
            while (idx < toks.size) {
                val t = toks[idx].lowercase()
                when {
                    t == "italic" || t == "oblique" -> out.add("font-style" to t)
                    t == "bold" || t == "bolder" || t == "lighter" || (t.length == 3 && t.toIntOrNull() != null) -> out.add("font-weight" to t)
                    t == "normal" || t == "small-caps" -> {}
                    else -> break
                }
                idx++
            }
            if (idx >= toks.size) return out
            val size = toks[idx].substringBefore('/')
            if (Length.parse(size) == null) return out
            out.add("font-size" to size)
            idx++
            if (idx < toks.size) out.add("font-family" to toks.drop(idx).joinToString(" "))
            return out
        }
    }
}
