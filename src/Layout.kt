package kotbrowse

import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

data class Rect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val right: Double get() = x + w
    val bottom: Double get() = y + h
}

/** Measures text so layout can be independent of the painting backend. */
interface TextMeasurer {
    fun width(text: String, font: FontSpec): Double
    fun ascent(font: FontSpec): Double
    fun lineHeight(font: FontSpec): Double
}

/** Deterministic metrics (every glyph is half the font size wide) for tests. */
class FixedMeasurer : TextMeasurer {
    override fun width(text: String, font: FontSpec) = text.length * font.size * 0.5
    override fun ascent(font: FontSpec) = font.size
    override fun lineHeight(font: FontSpec) = font.size * 1.25
}

enum class BoxKind { BLOCK, INLINE, ANONYMOUS, TEXT, IMAGE }

/** A box of the layout tree. `x`, `y`, `width`, `height` describe the content box. */
class Box(
    val kind: BoxKind,
    val style: ComputedStyle,
    val element: Element? = null,
    val text: String = "",
    val image: BufferedImage? = null,
) {
    val children = mutableListOf<Box>()
    var x = 0.0
    var y = 0.0
    var width = 0.0
    var height = 0.0
    var margin = ZERO_EDGES
    var padding = ZERO_EDGES
    var border = ZERO_EDGES
    val lines = mutableListOf<Line>()

    val isBlockLevel: Boolean get() = kind == BoxKind.BLOCK || kind == BoxKind.ANONYMOUS
    val contentBox: Rect get() = Rect(x, y, width, height)
    val paddingBox: Rect get() = Rect(x - padding.left, y - padding.top, width + padding.left + padding.right, height + padding.top + padding.bottom)
    val borderBox: Rect
        get() = Rect(
            x - padding.left - border.left, y - padding.top - border.top,
            width + padding.left + padding.right + border.left + border.right,
            height + padding.top + padding.bottom + border.top + border.bottom,
        )
    val marginBottom: Double get() = borderBox.bottom + margin.bottom

    fun descendants(): Sequence<Box> = sequence {
        yield(this@Box)
        for (c in children) yieldAll(c.descendants())
    }

    fun dump(indent: Int = 0): String {
        val sb = StringBuilder()
        val pad = "  ".repeat(indent)
        val name = when (kind) {
            BoxKind.ANONYMOUS -> "anonymous"
            BoxKind.TEXT -> "text"
            else -> element?.let { e -> e.tag + (e.id?.let { "#$it" } ?: "") + e.classes.joinToString("") { ".$it" } } ?: kind.name.lowercase()
        }
        sb.append("$pad$name @ (${fmt(x)},${fmt(y)}) ${fmt(width)}x${fmt(height)}\n")
        for (line in lines) {
            sb.append("$pad  line y=${fmt(line.y)} h=${fmt(line.height)}:")
            for (f in line.fragments) sb.append(if (f.text != null) " \"${f.text}\"@${fmt(f.x)}" else " [img ${fmt(f.width)}x${fmt(f.ascent)}]@${fmt(f.x)}")
            sb.append('\n')
        }
        if (lines.isEmpty()) for (c in children) if (c.isBlockLevel) sb.append(c.dump(indent + 1))
        return sb.toString()
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else String.format("%.1f", v)
}

/** A positioned piece of a line: a word, a run of preformatted text, or an image. */
class Fragment(
    val x: Double,
    val baseline: Double,
    val width: Double,
    val ascent: Double,
    val descent: Double,
    val text: String?,
    val style: ComputedStyle,
    val image: BufferedImage?,
    val element: Element?,
)

class Line(val y: Double, val height: Double, val fragments: List<Fragment>)

/** Builds the box tree from the styled DOM. */
object BoxBuilder {
    fun build(styled: StyledNode, images: Map<Element, BufferedImage> = emptyMap()): Box {
        val root = node(styled, images) ?: Box(BoxKind.BLOCK, styled.style.copy(display = "block"), styled.node as? Element)
        if (!root.isBlockLevel) {
            val wrapper = Box(BoxKind.BLOCK, root.style.copy(display = "block"), root.element)
            wrapper.children.addAll(root.children)
            wrapAnonymous(wrapper)
            return wrapper
        }
        return root
    }

    private fun node(n: StyledNode, images: Map<Element, BufferedImage>): Box? {
        val st = n.style
        val e = n.node as? Element ?: return (n.node as Text).let { if (it.text.isEmpty()) null else Box(BoxKind.TEXT, st, text = it.text) }
        if (st.display == "none") return null
        if (e.tag == "img") return imageBox(e, st, images[e])
        if (e.tag == "br") return Box(BoxKind.INLINE, st, e)
        val box = Box(if (st.display == "block" || st.display == "list-item") BoxKind.BLOCK else BoxKind.INLINE, st, e)
        for (c in n.children) node(c, images)?.let { box.children.add(it) }
        if (box.kind == BoxKind.BLOCK) wrapAnonymous(box)
        return box
    }

    private fun imageBox(e: Element, st: ComputedStyle, img: BufferedImage?): Box? {
        val sized = st.width !is Length.Auto || st.height !is Length.Auto
        if (img == null && !sized) {
            val alt = e.attrs["alt"]?.trim().orEmpty()
            return if (alt.isEmpty()) null else Box(BoxKind.TEXT, st, e, text = alt)
        }
        val image = Box(BoxKind.IMAGE, st, e, image = img)
        if (st.display == "block") {
            val wrapper = Box(BoxKind.BLOCK, st, e)
            wrapper.children.add(Box(BoxKind.IMAGE, st.anonymous().copy(width = st.width, height = st.height), e, image = img))
            return wrapper
        }
        return image
    }

    /** Wraps runs of inline children of a block in anonymous block boxes when block children are present too. */
    private fun wrapAnonymous(box: Box) {
        if (box.children.none { it.isBlockLevel }) return
        val out = mutableListOf<Box>()
        var anon: Box? = null
        for (c in box.children) {
            if (c.isBlockLevel) {
                anon = null
                out.add(c)
            } else {
                if (anon == null) {
                    anon = Box(BoxKind.ANONYMOUS, box.style.anonymous())
                    out.add(anon)
                }
                anon.children.add(c)
            }
        }
        box.children.clear()
        box.children.addAll(out.filter { it.kind != BoxKind.ANONYMOUS || hasContent(it) })
    }

    private fun hasContent(box: Box): Boolean = box.descendants().any {
        it.kind == BoxKind.IMAGE || (it.kind == BoxKind.TEXT && (it.style.whiteSpace.startsWith("pre") || it.text.isNotBlank())) || it.element?.tag == "br"
    }
}

/** Block and inline layout. */
class Layouter(private val measurer: TextMeasurer, private val viewportWidth: Double) {
    fun layout(root: Box): Box {
        layoutBlock(root, Rect(0.0, 0.0, viewportWidth, 0.0), 0.0)
        return root
    }

    /** Lays out a block-level box inside `cb`, with the top of its margin box at `top`. */
    private fun layoutBlock(box: Box, cb: Rect, top: Double) {
        val st = box.style
        val fs = st.fontSize
        box.margin = st.margin.map { it.resolve(fs, cb.w) }
        box.padding = st.padding.map { it.resolve(fs, cb.w) }
        box.border = st.borderWidth
        val extras = box.padding.left + box.padding.right + box.border.left + box.border.right
        var width = if (st.width is Length.Auto) -1.0 else st.width.resolve(fs, cb.w)
        if (width < 0) {
            width = (cb.w - extras - box.margin.left - box.margin.right).coerceAtLeast(0.0)
            st.maxWidth?.let { mw -> width = min(width, mw.resolve(fs, cb.w)) }
            val free = cb.w - width - extras - box.margin.left - box.margin.right
            if (free > 0) box.margin = centred(box, st, free)
        } else {
            st.maxWidth?.let { mw -> width = min(width, mw.resolve(fs, cb.w)) }
            val free = cb.w - width - extras
            box.margin = centred(box, st, free - box.margin.left - box.margin.right)
        }
        box.width = width
        box.x = cb.x + box.margin.left + box.border.left + box.padding.left
        box.y = top + box.margin.top + box.border.top + box.padding.top

        if (box.children.any { it.isBlockLevel }) {
            var cursor = box.y
            var prevMargin = 0.0
            var first = true
            for (child in box.children) {
                val mt = child.style.margin.top.resolve(child.style.fontSize, box.width)
                val childTop = if (first) cursor else cursor - min(mt, prevMargin) // collapse adjacent vertical margins
                layoutBlock(child, Rect(box.x, box.y, box.width, 0.0), childTop)
                cursor = child.marginBottom
                prevMargin = child.margin.bottom
                first = false
            }
            box.height = (cursor - box.y).coerceAtLeast(0.0)
        } else {
            layoutInline(box)
        }
        if (st.height is Length.Px || st.height is Length.Em) box.height = st.height.resolve(fs, 0.0)
    }

    /** Distributes `free` horizontal space to `auto` margins (both auto centres the box). */
    private fun centred(box: Box, st: ComputedStyle, free: Double): Edges<Double> {
        val autoLeft = st.margin.left is Length.Auto
        val autoRight = st.margin.right is Length.Auto
        val m = box.margin
        return when {
            autoLeft && autoRight -> m.copy(left = free / 2, right = free / 2)
            autoLeft -> m.copy(left = free)
            autoRight -> m.copy(right = free)
            else -> m
        }
    }

    private sealed class Item {
        class Word(val text: String, val style: ComputedStyle, val width: Double, val spaceBefore: Boolean, val element: Element?) : Item()
        class Atomic(val box: Box, val w: Double, val h: Double, val spaceBefore: Boolean) : Item()
        object Break : Item()
    }

    private inner class Collector(val containerWidth: Double) {
        val items = mutableListOf<Item>()
        var pendingSpace = false

        fun collect(box: Box, element: Element?) {
            when (box.kind) {
                BoxKind.TEXT -> text(box, element)
                BoxKind.IMAGE -> {
                    val (w, h) = imageSize(box, containerWidth)
                    items.add(Item.Atomic(box, w, h, pendingSpace))
                    pendingSpace = false
                }
                BoxKind.INLINE -> {
                    if (box.element?.tag == "br") {
                        items.add(Item.Break)
                        pendingSpace = false
                    } else for (c in box.children) collect(c, box.element ?: element)
                }
                BoxKind.BLOCK, BoxKind.ANONYMOUS -> {
                    // a block inside inline content: keep it on its own lines
                    if (items.isNotEmpty()) items.add(Item.Break)
                    for (c in box.children) collect(c, box.element ?: element)
                    items.add(Item.Break)
                    pendingSpace = false
                }
            }
        }

        private fun text(box: Box, element: Element?) {
            val st = box.style
            if (st.whiteSpace == "pre" || st.whiteSpace == "pre-wrap") {
                val segments = box.text.replace("\r\n", "\n").replace("\t", "    ").split('\n')
                for ((k, seg) in segments.withIndex()) {
                    if (k > 0) items.add(Item.Break)
                    if (seg.isNotEmpty()) items.add(Item.Word(seg, st, measurer.width(seg, st.font), false, element))
                }
                pendingSpace = false
                return
            }
            var i = 0
            val t = box.text
            while (i < t.length) {
                if (t[i].isWhitespace()) {
                    if (st.whiteSpace == "pre-line" && t[i] == '\n') items.add(Item.Break) else pendingSpace = true
                    i++
                    continue
                }
                val start = i
                while (i < t.length && !t[i].isWhitespace()) i++
                val word = t.substring(start, i)
                items.add(Item.Word(word, st, measurer.width(word, st.font), pendingSpace, element))
                pendingSpace = false
            }
        }
    }

    private fun imageSize(box: Box, containerWidth: Double): Pair<Double, Double> {
        val st = box.style
        val iw = box.image?.width?.toDouble() ?: 0.0
        val ih = box.image?.height?.toDouble() ?: 0.0
        var w = if (st.width is Length.Auto) -1.0 else st.width.resolve(st.fontSize, containerWidth)
        var h = if (st.height is Length.Auto) -1.0 else st.height.resolve(st.fontSize, 0.0)
        if (w < 0 && h < 0) {
            w = iw
            h = ih
        } else if (w < 0) {
            w = if (ih > 0) h * iw / ih else h
        } else if (h < 0) {
            h = if (iw > 0) w * ih / iw else w
        }
        return w to h
    }

    private class Placed(val item: Item, val x: Double, val width: Double)

    private fun layoutInline(box: Box) {
        val collector = Collector(box.width)
        for (c in box.children) collector.collect(c, box.element)
        val items = collector.items
        val wrap = box.style.whiteSpace != "nowrap" && box.style.whiteSpace != "pre"
        val lines = mutableListOf<List<Placed>>()
        var cur = mutableListOf<Placed>()
        var curW = 0.0
        fun newLine() {
            lines.add(cur)
            cur = mutableListOf()
            curW = 0.0
        }
        for (item in items) {
            when (item) {
                is Item.Break -> newLine()
                is Item.Word -> {
                    var space = if (cur.isNotEmpty() && item.spaceBefore) measurer.width(" ", item.style.font) else 0.0
                    if (wrap && cur.isNotEmpty() && curW + space + item.width > box.width + 1e-6) {
                        newLine()
                        space = 0.0
                    }
                    cur.add(Placed(item, curW + space, item.width))
                    curW += space + item.width
                }
                is Item.Atomic -> {
                    var space = if (cur.isNotEmpty() && item.spaceBefore) measurer.width(" ", item.box.style.font) else 0.0
                    if (wrap && cur.isNotEmpty() && curW + space + item.w > box.width + 1e-6) {
                        newLine()
                        space = 0.0
                    }
                    cur.add(Placed(item, curW + space, item.w))
                    curW += space + item.w
                }
            }
        }
        if (cur.isNotEmpty()) newLine()

        var y = box.y
        val ownFont = box.style.font
        for (placed in lines) {
            var ascent = 0.0
            var descent = 0.0
            if (placed.isEmpty()) {
                ascent = measurer.ascent(ownFont)
                descent = measurer.lineHeight(ownFont) - ascent
            }
            for (p in placed) when (val it = p.item) {
                is Item.Word -> {
                    val a = measurer.ascent(it.style.font)
                    ascent = max(ascent, a)
                    descent = max(descent, measurer.lineHeight(it.style.font) - a)
                }
                is Item.Atomic -> ascent = max(ascent, it.h)
                else -> {}
            }
            val lineWidth = placed.lastOrNull()?.let { it.x + it.width } ?: 0.0
            val slack = (box.width - lineWidth).coerceAtLeast(0.0)
            val dx = when (box.style.textAlign) {
                "center" -> slack / 2
                "right" -> slack
                else -> 0.0
            }
            val baseline = y + ascent
            val fragments = placed.map { p ->
                when (val it = p.item) {
                    is Item.Word -> Fragment(box.x + dx + p.x, baseline, p.width, measurer.ascent(it.style.font), measurer.lineHeight(it.style.font) - measurer.ascent(it.style.font), it.text, it.style, null, it.element)
                    is Item.Atomic -> Fragment(box.x + dx + p.x, baseline, p.width, it.h, 0.0, null, it.box.style, it.box.image, it.box.element)
                    else -> throw IllegalStateException("break in line")
                }
            }
            val height = ascent + descent
            box.lines.add(Line(y, height, fragments))
            y += height
        }
        box.height = y - box.y
    }
}
