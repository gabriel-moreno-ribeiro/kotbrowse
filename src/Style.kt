package kotbrowse

data class Edges<T>(val top: T, val right: T, val bottom: T, val left: T) {
    fun <R> map(f: (T) -> R): Edges<R> = Edges(f(top), f(right), f(bottom), f(left))
}

val ZERO_EDGES = Edges(0.0, 0.0, 0.0, 0.0)
val AUTO_EDGES: Edges<Length> = Edges(Length.Px(0.0), Length.Px(0.0), Length.Px(0.0), Length.Px(0.0))

data class FontSpec(val family: String, val size: Double, val bold: Boolean, val italic: Boolean)

/** The resolved style of one element after the cascade and inheritance. */
data class ComputedStyle(
    val display: String,
    val fontSize: Double,
    val bold: Boolean,
    val italic: Boolean,
    val fontFamily: String,
    val color: Color,
    val backgroundColor: Color,
    val textAlign: String,
    val underline: Boolean,
    val whiteSpace: String,
    val width: Length,
    val height: Length,
    val maxWidth: Length?,
    val margin: Edges<Length>,
    val padding: Edges<Length>,
    val borderWidth: Edges<Double>,
    val borderColor: Edges<Color>,
    val listStyle: String,
) {
    val font: FontSpec get() = FontSpec(fontFamily, fontSize, bold, italic)

    /** Style for an anonymous block box: inherits text properties, has no box decorations. */
    fun anonymous(): ComputedStyle = copy(
        display = "block", backgroundColor = Color.TRANSPARENT, width = Length.Auto, height = Length.Auto, maxWidth = null,
        margin = AUTO_EDGES, padding = AUTO_EDGES, borderWidth = ZERO_EDGES,
    )

    companion object {
        val INITIAL = ComputedStyle(
            display = "inline", fontSize = 16.0, bold = false, italic = false, fontFamily = "serif",
            color = Color.BLACK, backgroundColor = Color.TRANSPARENT, textAlign = "left", underline = false,
            whiteSpace = "normal", width = Length.Auto, height = Length.Auto, maxWidth = null,
            margin = AUTO_EDGES, padding = AUTO_EDGES, borderWidth = ZERO_EDGES,
            borderColor = Edges(Color.BLACK, Color.BLACK, Color.BLACK, Color.BLACK), listStyle = "disc",
        )
    }
}

class StyledNode(val node: Node, val style: ComputedStyle, val children: List<StyledNode>) {
    fun find(pred: (Element) -> Boolean): StyledNode? {
        if (node is Element && pred(node)) return this
        for (c in children) c.find(pred)?.let { return it }
        return null
    }
}

/** The default look of HTML elements, applied below every author stylesheet. */
const val USER_AGENT_CSS = """
html, body, div, p, h1, h2, h3, h4, h5, h6, ul, ol, li, pre, blockquote, header, footer, main, section, article,
nav, aside, form, table, tr, hr, figure, figcaption, fieldset, dl, dt, dd, address, center, details, summary { display: block; }
head, script, style, title, meta, link, template, noscript, base { display: none; }
[hidden] { display: none; }
body { margin: 8px; }
h1 { font-size: 2em; font-weight: bold; margin: 0.67em 0; }
h2 { font-size: 1.5em; font-weight: bold; margin: 0.83em 0; }
h3 { font-size: 1.17em; font-weight: bold; margin: 1em 0; }
h4 { font-weight: bold; margin: 1.33em 0; }
h5 { font-size: 0.83em; font-weight: bold; margin: 1.67em 0; }
h6 { font-size: 0.67em; font-weight: bold; margin: 2.33em 0; }
p, blockquote, ul, ol, dl, pre, figure { margin: 1em 0; }
blockquote, figure { margin-left: 40px; margin-right: 40px; }
ul, ol { padding-left: 40px; }
dd { margin-left: 40px; }
li { display: list-item; }
ul { list-style-type: disc; }
ol { list-style-type: decimal; }
a { color: #0000ee; text-decoration: underline; }
b, strong { font-weight: bold; }
i, em, cite, var, dfn { font-style: italic; }
pre, code, kbd, samp, tt { font-family: monospace; }
pre { white-space: pre; }
hr { border-top: 1px solid gray; margin: 8px 0; }
center { text-align: center; }
small, sub, sup { font-size: 0.83em; }
big { font-size: 1.17em; }
u, ins { text-decoration: underline; }
td, th { display: inline-block; padding: 1px 4px; }
th { font-weight: bold; }
mark { background-color: yellow; }
"""

/** Runs the cascade: user agent rules, then author stylesheets in order, then inline styles. */
class Styler(sheets: List<Stylesheet>) {
    private class Entry(val selector: Selector, val declarations: List<Declaration>, val origin: Int, val order: Int)

    private val entries: List<Entry>

    init {
        var order = 0
        val all = listOf(Stylesheet.parse(USER_AGENT_CSS)) + sheets
        entries = all.flatMapIndexed { si, sheet ->
            sheet.rules.flatMap { rule -> rule.selectors.map { Entry(it, rule.declarations, if (si == 0) 0 else 1, order++) } }
        }
    }

    private class Matched(val important: Boolean, val origin: Int, val specificity: Specificity, val order: Int, val name: String, val value: String)

    /** The winning specified value of every property set on the element. */
    fun specifiedValues(e: Element): Map<String, String> {
        val matched = mutableListOf<Matched>()
        val none = Specificity(0, 0, 0)
        // presentational attributes sit below every stylesheet
        fun attrLength(v: String) = if (v.trim().toDoubleOrNull() != null) "${v.trim()}px" else v.trim()
        e.attrs["width"]?.let { if (e.tag in setOf("img", "table", "td", "th", "hr")) matched.add(Matched(false, -1, none, 0, "width", attrLength(it))) }
        e.attrs["height"]?.let { if (e.tag == "img") matched.add(Matched(false, -1, none, 0, "height", attrLength(it))) }
        e.attrs["bgcolor"]?.let { matched.add(Matched(false, -1, none, 0, "background-color", it)) }
        e.attrs["align"]?.let { matched.add(Matched(false, -1, none, 0, "text-align", it)) }
        if (e.tag == "font") e.attrs["color"]?.let { matched.add(Matched(false, -1, none, 0, "color", it)) }
        for (en in entries) {
            if (!en.selector.matches(e)) continue
            for (d in en.declarations) matched.add(Matched(d.important, en.origin, en.selector.specificity, en.order, d.name, d.value))
        }
        e.attrs["style"]?.let { inline ->
            for (d in Stylesheet.parseDeclarations(inline)) matched.add(Matched(d.important, 2, none, Int.MAX_VALUE, d.name, d.value))
        }
        matched.sortWith(compareBy({ it.important }, { it.origin }, { it.specificity }, { it.order }))
        val out = HashMap<String, String>()
        for (m in matched) out[m.name] = m.value.trim()
        return out
    }

    private val FONT_SIZE_KEYWORDS = mapOf(
        "xx-small" to 9.0, "x-small" to 10.0, "small" to 13.0, "medium" to 16.0, "large" to 18.0, "x-large" to 24.0, "xx-large" to 32.0,
    )

    fun compute(e: Element, parent: ComputedStyle?): ComputedStyle {
        val sv = specifiedValues(e)
        val p = parent ?: ComputedStyle.INITIAL
        fun value(name: String): String? = sv[name]?.takeUnless { it.equals("inherit", true) }
        fun inheritedRaw(name: String): String? = sv[name]?.takeUnless { it.equals("inherit", true) || it.equals("initial", true) }

        val fontSize = when (val raw = inheritedRaw("font-size")?.lowercase()) {
            null -> p.fontSize
            "larger" -> p.fontSize * 1.2
            "smaller" -> p.fontSize / 1.2
            in FONT_SIZE_KEYWORDS -> FONT_SIZE_KEYWORDS.getValue(raw)
            else -> when (val len = Length.parse(raw)) {
                is Length.Px -> len.v
                is Length.Em -> len.v * p.fontSize
                is Length.Percent -> len.v / 100 * p.fontSize
                else -> p.fontSize
            }
        }
        val bold = when (val w = inheritedRaw("font-weight")?.lowercase()) {
            null -> p.bold
            "bold", "bolder" -> true
            "normal", "lighter" -> false
            else -> (w.toIntOrNull() ?: 400) >= 600
        }
        val italic = when (inheritedRaw("font-style")?.lowercase()) {
            null -> p.italic
            "italic", "oblique" -> true
            else -> false
        }
        val fontFamily = inheritedRaw("font-family") ?: p.fontFamily
        val color = Color.parse(inheritedRaw("color")) ?: p.color
        val backgroundColor = Color.parse(value("background-color")) ?: Color.TRANSPARENT
        val textAlign = when (val t = inheritedRaw("text-align")?.lowercase()) {
            null -> p.textAlign
            "start" -> "left"
            "end" -> "right"
            else -> t
        }
        val underline = when (val d = inheritedRaw("text-decoration")?.lowercase()) {
            null -> p.underline
            else -> d.contains("underline")
        }
        val whiteSpace = inheritedRaw("white-space")?.lowercase() ?: p.whiteSpace
        val display = when (val d = value("display")?.lowercase()) {
            null -> "inline"
            "none" -> "none"
            "block", "flex", "grid", "table", "table-row", "table-row-group", "table-header-group", "table-footer-group", "flow-root" -> "block"
            "list-item" -> "list-item"
            "inline-block", "inline-flex", "inline-grid", "table-cell", "inline-table" -> "inline-block"
            else -> "inline"
        }
        val width = Length.parse(value("width")) ?: Length.Auto
        val height = Length.parse(value("height")) ?: Length.Auto
        val maxWidth = Length.parse(value("max-width"))?.takeUnless { it is Length.Auto }
        fun edges(prefix: String, suffix: String = ""): Edges<Length> {
            fun side(s: String) = Length.parse(value("$prefix-$s$suffix")) ?: Length.Px(0.0)
            return Edges(side("top"), side("right"), side("bottom"), side("left"))
        }
        val borderWidth = Edges("top", "right", "bottom", "left").map { side ->
            val style = value("border-$side-style")?.lowercase() ?: "none"
            if (style == "none" || style == "hidden") 0.0
            else when (val w = value("border-$side-width")?.lowercase() ?: "medium") {
                "thin" -> 1.0
                "medium" -> 3.0
                "thick" -> 5.0
                else -> Length.parse(w)?.resolve(fontSize, 0.0) ?: 3.0
            }
        }
        val borderColor = Edges("top", "right", "bottom", "left").map { side -> Color.parse(value("border-$side-color")) ?: color }
        val listStyle = inheritedRaw("list-style-type")?.lowercase() ?: p.listStyle
        return ComputedStyle(
            display, fontSize, bold, italic, fontFamily, color, backgroundColor, textAlign, underline, whiteSpace,
            width, height, maxWidth, edges("margin"), edges("padding"), borderWidth, borderColor, listStyle,
        )
    }

    fun styleTree(root: Element): StyledNode = build(root, null)

    private fun build(n: Node, parent: ComputedStyle?): StyledNode = when (n) {
        is Element -> {
            val st = compute(n, parent)
            StyledNode(n, st, if (st.display == "none") emptyList() else n.children.map { build(it, st) })
        }
        is Text -> StyledNode(n, parent ?: ComputedStyle.INITIAL, emptyList())
    }
}
