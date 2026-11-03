package ua.komora.net

/**
 * A release's description from GitHub, made into plain short text for "What's new": no code blocks
 * (checksums and the like), no Markdown marks, no empty runs of lines. Shown as plain text only.
 */
object ReleaseNotes {
    const val MAX_CHARS = 1500
    private val HEX = Regex("^[0-9a-fA-F]{32,}(\\s+\\S+)?$")

    fun clean(body: String?): String {
        if (body.isNullOrBlank()) return ""
        val out = mutableListOf<String>()
        var inCode = false
        for (raw in body.replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            if (line.startsWith("```")) { inCode = !inCode; continue }
            if (inCode || HEX.matches(line) || line.equals("SHA-256:", ignoreCase = true)) continue
            val text = line
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[*-]\\s+"), "• ")
                .replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            if (text.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out += text
        }
        val joined = out.joinToString("\n").trim()
        return if (joined.length <= MAX_CHARS) joined else joined.take(MAX_CHARS).trimEnd() + "…"
    }
}
