package kotbrowse

import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private fun usage() {
    System.err.println(
        """
        usage: kotbrowse <url or file> [-o page.png] [--width 800] [--dump] [--text]
          -o, --output   PNG file to write (default page.png)
          -w, --width    viewport width in pixels (default 800)
          --dump         print the layout tree
          --text         print the page as plain text
        """.trimIndent(),
    )
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    var target: String? = null
    var output = "page.png"
    var width = 800
    var dump = false
    var text = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-o", "--output" -> output = args.getOrNull(++i) ?: run { usage(); exitProcess(2) }
            "-w", "--width" -> width = args.getOrNull(++i)?.toIntOrNull() ?: run { usage(); exitProcess(2) }
            "--dump" -> dump = true
            "--text" -> text = true
            "-h", "--help" -> {
                usage()
                return
            }
            else -> target = args[i]
        }
        i++
    }
    val url = target ?: run {
        usage()
        exitProcess(2)
    }
    val resolved = when {
        url.contains("://") -> url
        File(url).exists() -> File(url).absoluteFile.toURI().toString()
        else -> "https://$url"
    }
    val browser = Browser(width, log = { System.err.println("  $it") })
    val page = try {
        browser.load(resolved)
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
    println("title: ${page.title}")
    if (dump) print(page.root.dump())
    if (text) print(page.plainText())
    ImageIO.write(browser.render(page, 600), "png", File(output))
    println("rendered ${page.width}x${page.height} page to $output")
}
