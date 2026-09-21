package app.soundbound.core.book.epub

/**
 * Zip entries are flat strings, and EPUBs reference each other with relative URLs that freely
 * use `../`, percent-encoding and fragments. Everything that turns an href into an entry name
 * goes through here, which keeps one set of rules in one place.
 */
internal object ZipPaths {

    /** Resolves [href] against [baseDirectory] and normalises `.`/`..` away. */
    fun resolve(baseDirectory: String, href: String): String {
        val withoutFragment = href.substringBefore('#')
        if (withoutFragment.isEmpty()) return normalise(baseDirectory)
        val decoded = percentDecode(withoutFragment)
        // An href starting with '/' is absolute within the container.
        val combined = if (decoded.startsWith('/')) decoded.removePrefix("/") else baseDirectory + decoded
        return normalise(combined)
    }

    fun normalise(path: String): String {
        val out = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }

    fun directoryOf(path: String): String =
        path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    fun fragmentOf(href: String): String? =
        href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::percentDecode)

    /**
     * Decodes `%XX` escapes as UTF-8. Deliberately lenient: a stray `%` in a filename is far
     * more common in real EPUBs than a genuinely malformed escape, so we pass it through.
     */
    fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    bytes.add(decoded.toByte())
                    i += 3
                    continue
                }
            }
            c.toString().toByteArray(Charsets.UTF_8).forEach(bytes::add)
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
