package kotbrowse

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

class Resource(val url: String, val bytes: ByteArray, val contentType: String) {
    /** Decodes the body using the HTTP charset, a `<meta charset>` hint, or UTF-8. */
    val text: String
        get() {
            val fromHeader = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
            val fromMeta = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
            val name = fromHeader ?: fromMeta ?: "UTF-8"
            val charset = try {
                Charset.forName(name)
            } catch (e: Exception) {
                Charsets.UTF_8
            }
            return String(bytes, charset)
        }
}

/** Fetches `http(s):` and `file:` URLs with the JDK's HTTP client, following redirects. */
object Net {
    fun fetch(url: String): Resource {
        if (url.startsWith("file:")) {
            val path = Paths.get(URI(url))
            return Resource(url, Files.readAllBytes(path), guessType(path.toString()))
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw IOException("unsupported URL scheme: $url")
        var current = url
        repeat(8) {
            val conn = URI(current).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "kotbrowse/1.0")
            conn.setRequestProperty("Accept", "text/html,text/css,image/*,*/*")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                conn.disconnect()
                current = resolve(current, location)
                return@repeat
            }
            if (code >= 400) throw IOException("HTTP $code for $current")
            val bytes = conn.inputStream.use { it.readBytes() }
            return Resource(current, bytes, conn.contentType ?: guessType(current))
        }
        throw IOException("too many redirects for $url")
    }

    fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.trim()).toString()
    } catch (e: Exception) {
        ref
    }

    private fun guessType(name: String): String = when (name.substringAfterLast('.', "").lowercase().substringBefore('?')) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }
}
