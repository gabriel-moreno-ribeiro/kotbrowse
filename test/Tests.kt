package kotbrowse

import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private var passed = 0
private var failed = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    if (condition) passed++ else {
        failed++
        println("FAIL: $name ${if (detail.isNotEmpty()) "($detail)" else ""}")
    }
}

private fun <T> eq(name: String, actual: T, expected: T) = check(name, actual == expected, "expected $expected, got $actual")

private fun near(name: String, actual: Double, expected: Double) = check(name, Math.abs(actual - expected) < 0.01, "expected $expected, got $actual")

private fun htmlTests() {
    val doc = Html.parse(
        """<!DOCTYPE html><html><head><title>T</title></head>
        <body><p class="a b" id=x>Hi &amp; bye<br>ok</p><img src=a.png alt='pic'><!-- comment --><ul><li>one<li>two</ul></body></html>""",
    )
    eq("root is html", doc.tag, "html")
    val body = doc.find("body")!!
    val p = doc.find("p")!!
    eq("p classes", p.classes, setOf("a", "b"))
    eq("p id", p.id, "x")
    eq("entity decoded", (p.children[0] as Text).text, "Hi & bye")
    eq("br is void", (p.children[1] as Element).children.size, 0)
    eq("text after br", (p.children[2] as Text).text, "ok")
    val img = doc.find("img")!!
    eq("img attrs", img.attrs, mapOf("src" to "a.png", "alt" to "pic"))
    check("comment dropped", body.children.none { it is Text && it.text.contains("comment") })
    val ul = doc.find("ul")!!
    eq("li implicitly closed", ul.children.filterIsInstance<Element>().map { it.tag }, listOf("li", "li"))
    eq("li text", (ul.children[1] as Element).textContent(), "two")
    eq("title", doc.find("title")!!.textContent(), "T")

    val bare = Html.parse("<p>hi")
    eq("implicit html", bare.tag, "html")
    eq("implicit body", bare.children.filterIsInstance<Element>().map { it.tag }, listOf("body"))
    eq("content in body", bare.find("body")!!.find("p")!!.textContent(), "hi")

    val script = Html.parse("<script>if (a < b && c) x()</script><p>after</p>")
    eq("raw script text", script.find("script")!!.textContent(), "if (a < b && c) x()")
    eq("element after script", script.find("p")!!.textContent(), "after")

    val implicitP = Html.parse("<p>a<div>b</div>c")
    val b = implicitP.find("body")!!
    eq("p closed by div", b.children.filterIsInstance<Element>().map { it.tag }, listOf("p", "div"))
    eq("p only has its text", implicitP.find("p")!!.textContent(), "a")

    eq("numeric entities", Html.decode("&#65;&#x42;&nbsp;&copy;&unknown;"), "AB ©&unknown;")
    val self = Html.parse("<div><br/><span>x</span></div>")
    eq("self-closing br", self.find("div")!!.children.filterIsInstance<Element>().map { it.tag }, listOf("br", "span"))
    val unquoted = Html.parse("<a href=/x class=y title=\"t t\">link</a>")
    eq("unquoted attrs", unquoted.find("a")!!.attrs, mapOf("href" to "/x", "class" to "y", "title" to "t t"))
}

private fun cssTests() {
    eq("specificity #a .b c", Selector.parse("#a .b c").specificity, Specificity(1, 1, 1))
    eq("specificity div > p.x", Selector.parse("div > p.x").specificity, Specificity(0, 1, 2))
    check("specificity ordering", Specificity(0, 1, 0) > Specificity(0, 0, 9))

    val dom = Html.parse("<div id=main><p class='x y' data-k=v>t<span>s</span></p></div>")
    val p = dom.find("p")!!
    val span = dom.find("span")!!
    check("#main p.x matches", Selector.parse("#main p.x").matches(p))
    check("descendant matches deep", Selector.parse("div span").matches(span))
    check("child does not skip levels", !Selector.parse("div > span").matches(span))
    check("child matches direct", Selector.parse("p > span").matches(span))
    check("missing class fails", !Selector.parse("p.z").matches(p))
    check("attribute presence", Selector.parse("[data-k]").matches(p))
    check("attribute value", Selector.parse("p[data-k=v]").matches(p))
    check("attribute wrong value", !Selector.parse("p[data-k=w]").matches(p))
    check("universal", Selector.parse("*").matches(span))
    check("pseudo-class never matches", !Selector.parse("p:hover").matches(p))
    check("body is not ancestor of itself", !Selector.parse("p p").matches(p))

    val sheet = Stylesheet.parse(
        """
        /* comment { with braces } */
        @import url(x.css);
        @media print { p { color: red } }
        @media screen { .screen { color: blue } }
        p, div { margin: 1px 2px; border: 1px solid red; background: url(x.png) rgb(1, 2, 3) }
        a:hover { color: red }
        h1 { font: italic bold 20px Georgia, serif !important; padding: 1px 2px 3px }
        """,
    )
    eq("rules parsed (unsupported-only rule dropped)", sheet.rules.size, 3)
    val screen = sheet.rules[0]
    eq("media screen included", screen.selectors[0].parts[0].first.classes, listOf("screen"))
    val decls = sheet.rules[1].declarations.associate { it.name to it.value }
    eq("margin expanded", decls["margin-left"], "2px")
    eq("margin top", decls["margin-top"], "1px")
    eq("border expanded width", decls["border-left-width"], "1px")
    eq("border expanded style", decls["border-top-style"], "solid")
    eq("border expanded color", decls["border-bottom-color"], "red")
    eq("background colour extracted", decls["background-color"], "rgb(1, 2, 3)")
    val h1 = sheet.rules[2].declarations
    check("important flag", h1.first { it.name == "font-size" }.important)
    eq("font shorthand size", h1.first { it.name == "font-size" }.value, "20px")
    eq("font shorthand family", h1.first { it.name == "font-family" }.value, "Georgia, serif")
    eq("font shorthand weight", h1.first { it.name == "font-weight" }.value, "bold")
    eq("padding three values", h1.first { it.name == "padding-left" }.value, "2px")
    eq("padding bottom", h1.first { it.name == "padding-bottom" }.value, "3px")

    eq("color hex3", Color.parse("#fff"), Color(255, 255, 255))
    eq("color hex6", Color.parse("#123456"), Color(0x12, 0x34, 0x56))
    eq("color rgb", Color.parse("rgb(10, 20, 30)"), Color(10, 20, 30))
    eq("color rgba", Color.parse("rgba(0,0,0,0.5)"), Color(0, 0, 0, 128))
    eq("color named", Color.parse("Red"), Color(255, 0, 0))
    eq("color invalid", Color.parse("nope"), null)
    eq("length px", Length.parse("12px"), Length.Px(12.0))
    eq("length em", Length.parse("1.5em"), Length.Em(1.5))
    eq("length percent", Length.parse("50%"), Length.Percent(50.0))
    eq("length auto", Length.parse("auto"), Length.Auto)
    eq("length zero", Length.parse("0"), Length.Px(0.0))
    eq("length junk", Length.parse("big"), null)
}

private fun styleTests() {
    val css = """
        body { color: red; font-size: 20px }
        span { color: blue }
        .big { font-size: 200% }
        p { margin-top: 5px } p { margin-top: 7px }
        #x { margin-top: 1px }
        div.imp { color: green !important } div { color: black }
        .center { text-align: center }
    """
    val dom = Html.parse(
        """<html><head><style>$css</style></head><body>
        <p id=x>text <span>s</span> <strong>b</strong> <em class=big>e</em></p>
        <div class=imp><a href=#>link</a></div>
        <div style="color: #010203; display: none"><b>hidden</b></div>
        <section hidden></section>
        <div class=center><span>c</span></div>
        <ul><li>item</li></ul>
        </body></html>""",
    )
    val styler = Styler(listOf(Stylesheet.parse(css)))
    val tree = styler.styleTree(dom)
    fun style(tag: String, pred: (Element) -> Boolean = { true }) = tree.find { it.tag == tag && pred(it) }!!.style
    eq("body font-size", style("body").fontSize, 20.0)
    eq("p inherits colour", style("p").color, Color(255, 0, 0))
    eq("span own colour", style("span") { it.parent?.tag == "p" }.color, Color(0, 0, 255))
    check("strong is bold via UA", style("strong").bold)
    check("em is italic", style("em").italic)
    eq("percentage font-size", style("em").fontSize, 40.0)
    eq("id beats later tag rule", style("p").margin.top, Length.Px(1.0))
    eq("important beats later rule", style("div") { it.classes.contains("imp") }.color, Color(0, 128, 0))
    eq("a gets UA colour and underline", style("a").let { it.color to it.underline }, Color(0, 0, 0xee) to true)
    eq("inline style display none", style("div") { "style" in it.attrs }.display, "none")
    check("children of display:none not styled", tree.find { it.tag == "b" } == null)
    eq("hidden attribute", style("section").display, "none")
    eq("head is display none", style("head").display, "none")
    eq("div is block", style("div") { it.classes.contains("center") }.display, "block")
    eq("span is inline", style("span") { it.parent?.tag == "div" }.display, "inline")
    eq("text-align inherited", style("span") { it.parent?.tag == "div" }.textAlign, "center")
    eq("li is list-item", style("li").display, "list-item")
    eq("ul padding from UA", style("ul").padding.left, Length.Px(40.0))
    eq("body margin from UA", style("body").margin.top, Length.Px(8.0))
    eq("h1 default", Styler(emptyList()).styleTree(Html.parse("<h1>x</h1>")).find { it.tag == "h1" }!!.style.fontSize, 32.0)
}

private fun layoutOf(html: String, width: Double = 800.0): Box {
    val tree = Styler(emptyList()).styleTree(Html.parse(html))
    val root = BoxBuilder.build(tree)
    return Layouter(FixedMeasurer(), width).layout(root)
}

private fun Box.byTag(tag: String): Box = descendants().first { it.element?.tag == tag && it.kind != BoxKind.TEXT }

private fun layoutTests() {
    val r1 = layoutOf("""<body style="margin:0"><div style="width:100px;height:50px"></div><div style="height:20px"></div></body>""")
    val body = r1.byTag("body")
    eq("body fills viewport", body.width, 800.0)
    val divs = body.children
    eq("fixed width", divs[0].width, 100.0)
    eq("fixed height", divs[0].height, 50.0)
    eq("second div below first", divs[1].y, 50.0)
    eq("auto width fills", divs[1].width, 800.0)
    eq("body height wraps children", body.height, 70.0)

    val r2 = layoutOf("""<body style="margin:0"><div style="margin:10px;padding:5px;border:2px solid black;width:100px;height:10px"></div></body>""")
    val d = r2.byTag("div")
    eq("content x after margin+border+padding", d.x, 17.0)
    eq("content y", d.y, 17.0)
    eq("border box", d.borderBox, Rect(10.0, 10.0, 114.0, 24.0))
    eq("parent height includes margins", r2.byTag("body").height, 44.0)

    val r3 = layoutOf("""<body style="margin:0"><div style="width:200px;margin:0 auto;height:1px"></div></body>""")
    eq("auto margins centre", r3.byTag("div").x, 300.0)

    val r4 = layoutOf("""<body style="margin:0"><p style="height:10px">a</p><p style="height:10px">b</p></body>""")
    val ps = r4.byTag("body").children
    eq("first p top margin (1em)", ps[0].y, 16.0)
    eq("sibling margins collapse", ps[1].y, 16.0 + 10.0 + 16.0)

    val r5 = layoutOf("""<body style="margin:0">aaaa bbbb cccc</body>""", width = 100.0)
    val b5 = r5.byTag("body")
    eq("two lines", b5.lines.size, 2)
    eq("first line words", b5.lines[0].fragments.map { it.text }, listOf("aaaa", "bbbb"))
    eq("second word after a space", b5.lines[0].fragments[1].x, 40.0)
    eq("second line y", b5.lines[1].y, 20.0)
    eq("second line word", b5.lines[1].fragments[0].text, "cccc")
    eq("body height = 2 lines", b5.height, 40.0)

    val r6 = layoutOf("""<body style="margin:0;text-align:center">aa<br>bbbb</body>""", width = 100.0)
    val b6 = r6.byTag("body")
    eq("br forces a break", b6.lines.size, 2)
    eq("centred line", b6.lines[0].fragments[0].x, 42.0)
    eq("centred second line", b6.lines[1].fragments[0].x, 34.0)

    val r7 = layoutOf("""<body style="margin:0"><div>text<p style="margin:0">para</p>more</div></body>""")
    val div = r7.byTag("div")
    eq("anonymous boxes around block", div.children.map { it.kind }, listOf(BoxKind.ANONYMOUS, BoxKind.BLOCK, BoxKind.ANONYMOUS))
    eq("anonymous line", div.children[0].lines[0].fragments[0].text, "text")
    eq("stacked heights", div.children[2].y, 40.0)

    val r8 = layoutOf("""<body style="margin:0"><pre style="margin:0">a  b
c</pre></body>""")
    val pre = r8.byTag("pre")
    eq("pre keeps lines", pre.lines.size, 2)
    eq("pre keeps spaces", pre.lines[0].fragments[0].text, "a  b")
    eq("pre width of run", pre.lines[0].fragments[0].width, 32.0)

    val r9 = layoutOf("""<body style="margin:0"><ul style="margin:0"><li>one</li><li>two</li></ul></body>""")
    val lis = r9.byTag("ul").children
    eq("li indented by ul padding", lis[0].x, 40.0)
    eq("second li below", lis[1].y, 20.0)

    val r10 = layoutOf("""<body style="margin:0"><img width=50 height=30> word</body>""")
    val b10 = r10.byTag("body")
    eq("image is an atomic inline", b10.lines[0].fragments[0].text, null)
    eq("image width", b10.lines[0].fragments[0].width, 50.0)
    eq("line height grows to image", b10.lines[0].height, 34.0)
    eq("word after image", b10.lines[0].fragments[1].x, 58.0)

    val r11 = layoutOf("""<body style="margin:0"><p style="margin:0">a <b>bb</b> c</p></body>""")
    val frags = r11.byTag("p").lines[0].fragments
    eq("inline nesting flattens to words", frags.map { it.text }, listOf("a", "bb", "c"))
    check("bold word keeps style", frags[1].style.bold && !frags[0].style.bold)
    eq("bold word element", frags[1].element?.tag, "b")
    eq("positions with spaces", frags.map { it.x }, listOf(0.0, 16.0, 40.0))

    val r12 = layoutOf("""<body style="margin:0"><div style="width:50%;height:5px"></div><div style="max-width:100px;height:5px;margin:0 auto"></div></body>""")
    eq("percent width", r12.byTag("body").children[0].width, 400.0)
    eq("max-width clamps auto width", r12.byTag("body").children[1].width, 100.0)
    eq("max-width box centred", r12.byTag("body").children[1].x, 350.0)

    val r13 = layoutOf("""<body style="margin:0"><img alt="fallback"></body>""")
    eq("broken image shows alt text", r13.byTag("body").lines[0].fragments[0].text, "fallback")

    val r14 = layoutOf("""<body style="margin:0;font-size:10px">x<br><br>y</body>""")
    eq("empty line from double br", r14.byTag("body").lines.size, 3)
    eq("empty line has font height", r14.byTag("body").lines[1].height, 12.5)

    val r15 = layoutOf("""<body style="margin:0"><span>only inline</span> <div>block</div></body>""")
    val dump = r15.dump()
    check("dump names boxes", dump.contains("body @") && dump.contains("anonymous @") && dump.contains("div @"), dump)
    check("dump shows words", dump.contains("\"only\"@0"), dump)
}

private fun paintTests() {
    val browser = Browser(200, FixedMeasurer())
    val page = browser.loadHtml(
        """<body style="margin:0;background:#00ff00">
        <div style="width:100px;height:50px;background:#ff0000"></div>
        <div style="width:20px;height:20px;border:5px solid #0000ff;margin-top:10px"></div>
        <p><a href=x>link</a></p></body>""",
    )
    eq("page height from layout", page.height > 90, true)
    val img = browser.render(page, 150)
    fun px(x: Int, y: Int) = img.getRGB(x, y) and 0xffffff
    eq("red box pixel", px(10, 10), 0xff0000)
    eq("canvas is body background", px(150, 10), 0x00ff00)
    eq("below content is background", px(10, 140), 0x00ff00)
    eq("border pixel", px(2, 62), 0x0000ff)
    eq("inside bordered box is background", px(15, 75), 0x00ff00)
    val commands = Painter.displayList(page.root)
    val text = commands.filterIsInstance<Command.DrawText>().first()
    eq("text command", text.text, "link")
    check("link is underlined and blue", text.underline && text.color == Color(0, 0, 0xee))

    val list = Browser(300, FixedMeasurer()).loadHtml("<ol><li>a</li><li>b</li></ol><ul><li>c</li></ul>")
    val markers = Painter.displayList(list.root)
    eq("ordered list numbers", markers.filterIsInstance<Command.DrawText>().map { it.text }.filter { it.endsWith(".") }, listOf("1.", "2."))
    eq("unordered list bullet", markers.filterIsInstance<Command.FillCircle>().size, 1)
    eq("plain text output", list.plainText().trim().lines(), listOf("a", "b", "c"))
}

private fun png(color: Int, w: Int, h: Int): ByteArray {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, color)
    val out = ByteArrayOutputStream()
    ImageIO.write(img, "png", out)
    return out.toByteArray()
}

private fun endToEndTest() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val logo = png(0x00ff00, 20, 20)
    fun route(path: String, type: String, body: () -> ByteArray) = server.createContext(path) { ex ->
        val bytes = body()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    route("/style.css", "text/css") {
        "body { margin: 0; background: #ffffff } h1 { color: #ff0000; margin: 0; font-size: 20px } .box { width: 40px; height: 40px; background: #0000ff }".toByteArray()
    }
    route("/logo.png", "image/png") { logo }
    route("/", "text/html; charset=utf-8") {
        """<html><head><title>Página &amp; teste</title><link rel="stylesheet" href="style.css"></head>
        <body><h1>Olá</h1><div class="box"></div><img src="/logo.png"><img src="/missing.png" alt="gone"></body></html>""".toByteArray()
    }
    server.createContext("/start") { ex ->
        ex.responseHeaders.add("Location", "/")
        ex.sendResponseHeaders(302, -1)
        ex.close()
    }
    server.createContext("/missing.png") { ex ->
        ex.sendResponseHeaders(404, -1)
        ex.close()
    }
    server.start()
    try {
        val base = "http://127.0.0.1:${server.address.port}"
        val messages = mutableListOf<String>()
        val browser = Browser(200, FixedMeasurer(), log = { messages.add(it) })
        val page = browser.load("$base/start")
        eq("redirect followed", page.url, "$base/")
        eq("title decoded", page.title, "Página & teste")
        eq("linked stylesheet applied to h1", page.styleOf { it.tag == "h1" }?.color, Color(255, 0, 0))
        eq("h1 font-size from stylesheet", page.styleOf { it.tag == "h1" }?.fontSize, 20.0)
        val img = browser.render(page)
        fun px(x: Int, y: Int) = img.getRGB(x, y) and 0xffffff
        eq("box painted blue at its position", px(5, 30), 0x0000ff)
        eq("image painted green", px(5, 70), 0x00ff00)
        check("missing image reported", messages.any { it.contains("missing.png") && it.contains("failed") }, messages.toString())
        check("missing image shows alt", page.plainText().contains("gone"))
        check("no exception for text with utf-8", page.plainText().contains("Olá"))
    } finally {
        server.stop(0)
    }
}

fun main() {
    System.setProperty("java.awt.headless", "true")
    val suites = listOf("html" to ::htmlTests, "css" to ::cssTests, "style" to ::styleTests, "layout" to ::layoutTests, "paint" to ::paintTests, "end-to-end" to ::endToEndTest)
    for ((name, suite) in suites) {
        try {
            suite()
        } catch (e: Throwable) {
            failed++
            println("FAIL: suite $name threw $e")
            e.printStackTrace()
        }
    }
    println("$passed passed, $failed failed")
    if (failed > 0) exitProcess(1)
}
