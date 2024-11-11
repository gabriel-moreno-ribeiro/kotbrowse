package kotbrowse

import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.roundToInt

/** Maps CSS font descriptions to AWT fonts. */
object Fonts {
    private val cache = HashMap<FontSpec, Font>()

    fun awt(spec: FontSpec): Font = cache.getOrPut(spec) {
        val family = spec.family.split(',').map { it.trim().trim('"', '\'').lowercase() }
        val name = when {
            family.any { it == "monospace" || it.contains("courier") || it.contains("mono") } -> Font.MONOSPACED
            family.any { it == "sans-serif" || it == "arial" || it == "helvetica" || it == "verdana" || it.contains("sans") || it == "system-ui" } -> Font.SANS_SERIF
            else -> Font.SERIF
        }
        var style = Font.PLAIN
        if (spec.bold) style = style or Font.BOLD
        if (spec.italic) style = style or Font.ITALIC
        Font(name, style, max(1, spec.size.roundToInt()))
    }
}

/** Real font metrics from AWT (headless). */
class AwtMeasurer : TextMeasurer {
    private val graphics = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }
    private val metrics = HashMap<FontSpec, FontMetrics>()
    private fun m(font: FontSpec) = metrics.getOrPut(font) { graphics.getFontMetrics(Fonts.awt(font)) }

    override fun width(text: String, font: FontSpec) = m(font).stringWidth(text).toDouble()
    override fun ascent(font: FontSpec) = m(font).ascent.toDouble()
    override fun lineHeight(font: FontSpec) = m(font).let { (it.ascent + it.descent + it.leading).toDouble() }
}

sealed class Command {
    data class FillRect(val rect: Rect, val color: Color) : Command()
    data class DrawText(val x: Double, val baseline: Double, val text: String, val font: FontSpec, val color: Color, val underline: Boolean, val width: Double) : Command()
    data class DrawImage(val rect: Rect, val image: BufferedImage?) : Command()
    data class FillCircle(val cx: Double, val cy: Double, val r: Double, val color: Color) : Command()
}

/** Turns a laid-out box tree into a display list and rasterises it. */
object Painter {
    fun displayList(root: Box): List<Command> {
        val out = mutableListOf<Command>()
        paint(root, out)
        return out
    }

    private fun paint(box: Box, out: MutableList<Command>) {
        if (!box.isBlockLevel) return
        val bb = box.borderBox
        val st = box.style
        if (st.backgroundColor.visible) out.add(Command.FillRect(bb, st.backgroundColor))
        val b = box.border
        if (b.top > 0) out.add(Command.FillRect(Rect(bb.x, bb.y, bb.w, b.top), st.borderColor.top))
        if (b.bottom > 0) out.add(Command.FillRect(Rect(bb.x, bb.bottom - b.bottom, bb.w, b.bottom), st.borderColor.bottom))
        if (b.left > 0) out.add(Command.FillRect(Rect(bb.x, bb.y, b.left, bb.h), st.borderColor.left))
        if (b.right > 0) out.add(Command.FillRect(Rect(bb.right - b.right, bb.y, b.right, bb.h), st.borderColor.right))
        if (st.display == "list-item" && st.listStyle != "none") marker(box, out)
        if (box.lines.isNotEmpty()) {
            for (line in box.lines) for (f in line.fragments) {
                if (f.text != null) out.add(Command.DrawText(f.x, f.baseline, f.text, f.style.font, f.style.color, f.style.underline, f.width))
                else out.add(Command.DrawImage(Rect(f.x, f.baseline - f.ascent, f.width, f.ascent), f.image))
            }
        } else for (c in box.children) paint(c, out)
    }

    private fun marker(box: Box, out: MutableList<Command>) {
        val fs = box.style.fontSize
        val firstLine = box.lines.firstOrNull() ?: box.descendants().firstOrNull { it.lines.isNotEmpty() }?.lines?.first()
        val baseline = firstLine?.fragments?.firstOrNull()?.baseline ?: (box.y + fs)
        when (box.style.listStyle) {
            "decimal" -> {
                val index = box.element?.let { li -> li.parent?.children?.filterIsInstance<Element>()?.filter { it.tag == "li" }?.indexOf(li)?.plus(1) } ?: 1
                val text = "$index."
                val width = text.length * fs * 0.5
                out.add(Command.DrawText(box.x - width - fs * 0.4, baseline, text, box.style.font, box.style.color, false, width))
            }
            "square" -> out.add(Command.FillRect(Rect(box.x - fs * 0.9, baseline - fs * 0.55, fs * 0.3, fs * 0.3), box.style.color))
            else -> out.add(Command.FillCircle(box.x - fs * 0.75, baseline - fs * 0.35, fs * 0.17, box.style.color))
        }
    }

    fun canvasColor(root: Box): Color {
        if (root.style.backgroundColor.visible) return root.style.backgroundColor
        val body = root.children.firstOrNull { it.element?.tag == "body" }
        if (body != null && body.style.backgroundColor.visible) return body.style.backgroundColor
        return Color.WHITE
    }

    fun render(commands: List<Command>, width: Int, height: Int, background: Color): BufferedImage {
        val img = BufferedImage(max(1, width), max(1, height), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = awt(background)
        g.fillRect(0, 0, img.width, img.height)
        for (c in commands) when (c) {
            is Command.FillRect -> {
                g.color = awt(c.color)
                g.fill(Rectangle2D.Double(c.rect.x, c.rect.y, c.rect.w, c.rect.h))
            }
            is Command.DrawText -> {
                g.font = Fonts.awt(c.font)
                g.color = awt(c.color)
                g.drawString(c.text, c.x.toFloat(), c.baseline.toFloat())
                if (c.underline) g.draw(Line2D.Double(c.x, c.baseline + 1.5, c.x + c.width, c.baseline + 1.5))
            }
            is Command.DrawImage -> {
                if (c.image != null) g.drawImage(c.image, c.rect.x.roundToInt(), c.rect.y.roundToInt(), c.rect.w.roundToInt(), c.rect.h.roundToInt(), null)
                else {
                    g.color = java.awt.Color(0xdd, 0xdd, 0xdd)
                    g.fill(Rectangle2D.Double(c.rect.x, c.rect.y, c.rect.w, c.rect.h))
                }
            }
            is Command.FillCircle -> {
                g.color = awt(c.color)
                g.fill(Ellipse2D.Double(c.cx - c.r, c.cy - c.r, c.r * 2, c.r * 2))
            }
        }
        g.dispose()
        return img
    }

    private fun awt(c: Color) = java.awt.Color(c.r, c.g, c.b, c.a)
}
