package kotbrowse

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

class Page(
    val url: String,
    val document: Element,
    val styled: StyledNode,
    val root: Box,
    val title: String,
    val width: Int,
    val height: Int,
) {
    fun styleOf(pred: (Element) -> Boolean): ComputedStyle? = styled.find(pred)?.style

    /** The page as plain text, one line per laid-out line. */
    fun plainText(): String {
        val sb = StringBuilder()
        for (box in root.descendants()) {
            if (box.lines.isEmpty()) continue
            for (line in box.lines) {
                sb.append(line.fragments.mapNotNull { it.text }.joinToString(" ")).append('\n')
            }
        }
        return sb.toString()
    }
}

/** Loads, styles, lays out and paints pages. */
class Browser(
    val width: Int = 800,
    val measurer: TextMeasurer = AwtMeasurer(),
    val fetch: (String) -> Resource = Net::fetch,
    val log: (String) -> Unit = {},
) {
    fun load(url: String): Page {
        val res = fetch(url)
        return loadHtml(res.text, res.url)
    }

    fun loadHtml(html: String, baseUrl: String = "about:blank"): Page {
        val dom = Html.parse(html)
        val sheets = mutableListOf<Stylesheet>()
        val images = HashMap<Element, BufferedImage>()
        for (e in dom.elements()) {
            when (e.tag) {
                "style" -> sheets.add(Stylesheet.parse(e.textContent()))
                "link" -> {
                    val rel = e.attrs["rel"]?.lowercase()?.split(' ') ?: emptyList()
                    val href = e.attrs["href"]
                    if ("stylesheet" in rel && href != null) {
                        val url = Net.resolve(baseUrl, href)
                        try {
                            sheets.add(Stylesheet.parse(fetch(url).text))
                            log("stylesheet $url")
                        } catch (ex: Exception) {
                            log("stylesheet $url failed: ${ex.message}")
                        }
                    }
                }
                "img" -> {
                    val src = e.attrs["src"] ?: continue
                    val url = Net.resolve(baseUrl, src)
                    try {
                        ImageIO.read(ByteArrayInputStream(fetch(url).bytes))?.let { images[e] = it }
                        log("image $url")
                    } catch (ex: Exception) {
                        log("image $url failed: ${ex.message}")
                    }
                }
            }
        }
        val styled = Styler(sheets).styleTree(dom)
        val root = BoxBuilder.build(styled, images)
        Layouter(measurer, width.toDouble()).layout(root)
        val bottom = root.descendants().maxOf { it.marginBottom }
        val title = dom.find("title")?.textContent()?.trim().orEmpty()
        return Page(baseUrl, dom, styled, root, title, width, max(1, ceil(bottom).toInt()))
    }

    fun render(page: Page, minHeight: Int = 0): BufferedImage =
        Painter.render(Painter.displayList(page.root), page.width, max(page.height, minHeight), Painter.canvasColor(page.root))
}
