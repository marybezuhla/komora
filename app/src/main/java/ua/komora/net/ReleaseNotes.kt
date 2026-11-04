package ua.komora.net

/**
 * A release's description from GitHub, made into plain short text for "What's new": no code blocks
 * (checksums and the like), no Markdown marks, no empty runs of lines. Shown as plain text only.
 */
object ReleaseNotes {
    const val MAX_CHARS = 4000
    private const val MAX_INPUT = 12000
    private val HEX = Regex("^[0-9a-fA-F]{32,}(\\s+\\S+)?$")

    // Characters that change the reading direction of text: never shown
    private val BIDI = Regex("[\u202A-\u202E\u2066-\u2069\u200E\u200F]")

    fun clean(body: String?): String {
        if (body.isNullOrBlank()) return ""
        // Only the beginning is ever shown: a huge description must not keep the patterns below busy
        val text = body.take(MAX_INPUT).replace("\r\n", "\n")
            .replace(BIDI, "")
            // HTML comments and tags are not text
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("</?[A-Za-z][^>]*>"), "")
            // Pictures: left out
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        val out = mutableListOf<String>()
        var inCode = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.startsWith("```")) { inCode = !inCode; continue }
            if (inCode || HEX.matches(line) || line.equals("SHA-256:", ignoreCase = true)) continue
            val text = line
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[*-]\\s+"), "• ")
                .replace("**", "").replace("`", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            if (text.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += text
        }
        val joined = out.joinToString("\n").trim()
        if (joined.length <= MAX_CHARS) return joined
        // Cut on a whole character, never inside a pair (emoji and the like)
        val end = if (Character.isHighSurrogate(joined[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
        return joined.substring(0, end).trimEnd() + "…"
    }

    /**
     * Releases often hold the same notes twice, Ukrainian and English, split by a "---" line: only the
     * part in the language shown is kept (the one with more Cyrillic letters for "uk"). Otherwise as is.
     */
    fun forLanguage(notes: String, lang: String): String {
        val parts = notes.split(Regex("(?m)^\\s*[-—_*]{3,}\\s*$")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return notes.trim()
        fun cyrillic(t: String) = t.count { it in '\u0400'..'\u04FF' }.toDouble() / t.count { it.isLetter() }.coerceAtLeast(1)
        return if (lang == "uk") parts.maxBy { cyrillic(it) } else parts.minBy { cyrillic(it) }
    }
}
