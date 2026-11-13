package dev.kindletts.tts

/**
 * Turns extracted Kindle page text into speakable sentences.
 *
 * Pages arrive with hard line wraps, hyphenated line breaks and occasional NBSPs.
 * Splitting happens here so the playback queue stays independent of the reader.
 */
object TextNormalizer {
    private val PARAGRAPH = Regex("\n\\s*\n")
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "e.g", "i.e", "no", "vol", "ch", "p", "pp", "a.m", "p.m", "u.s", "u.k",
    )

    fun normalize(raw: String): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace("\u00a0", " ")
        text = text.replace(Regex("(\\p{L})-\\n(\\p{L})"), "$1$2")
        text = text.lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
        // OCR returns one newline per visual line. Those are line wraps, not pauses;
        // keep blank lines as paragraph breaks and turn single newlines into spaces.
        text = text.replace(Regex("(?<!\n)\n(?!\n)"), " ")
        text = text.replace(Regex("\n{3,}"), "\n\n")
        return text.trim()
    }

    fun splitSentences(raw: String, maxChars: Int = 350): List<String> {
        val text = normalize(raw)
        if (text.isEmpty()) return emptyList()

        val sentences = mutableListOf<String>()
        for (paragraph in text.split(PARAGRAPH)) {
            var start = 0
            var index = 0
            while (index < paragraph.length) {
                val char = paragraph[index]
                if (char == '.' || char == '!' || char == '?' || char == '…') {
                    var end = index + 1
                    while (end < paragraph.length && paragraph[end] == char) end++
                    val atBoundary = end == paragraph.length || paragraph[end].isWhitespace()
                    if (atBoundary && (char != '.' || !isAbbreviation(paragraph, index))) {
                        addSentence(sentences, paragraph.substring(start, end), maxChars)
                        start = end
                        index = end
                        continue
                    }
                }
                index++
            }
            if (start < paragraph.length) {
                addSentence(sentences, paragraph.substring(start), maxChars)
            }
        }
        return sentences.filter { it.isNotBlank() }
    }

    private fun isAbbreviation(text: String, dotIndex: Int): Boolean {
        var end = dotIndex
        var start = end
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        val word = text.substring(start, end).trim().lowercase()
        if (word.isEmpty()) return false
        if (word.length == 1 && word[0].isLetter()) return true
        return word in ABBREVIATIONS
    }

    private fun addSentence(target: MutableList<String>, raw: String, maxChars: Int) {
        val sentence = raw.trim()
        if (sentence.isEmpty()) return
        if (sentence.length <= maxChars) {
            target.add(sentence)
            return
        }
        var buffer = ""
        for (part in sentence.split(Regex("(?<=[;:,—])\\s+"))) {
            val candidate = if (buffer.isEmpty()) part else "$buffer $part"
            if (candidate.length > maxChars && buffer.isNotEmpty()) {
                target.add(buffer.trim())
                buffer = part
            } else {
                buffer = candidate
            }
        }
        if (buffer.length <= maxChars) {
            if (buffer.isNotBlank()) target.add(buffer.trim())
            return
        }
        var rest = buffer.trim()
        while (rest.length > maxChars) {
            val cut = rest.lastIndexOf(' ', maxChars).takeIf { it > 40 } ?: maxChars
            target.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) target.add(rest)
    }

    /**
     * Removes text repeated at the start of [next] that already ends [previous].
     * Kindle's page turns do not normally overlap, but a duplicated heading or
     * repeated paragraph must never be spoken twice.
     */
    fun stripOverlap(previous: String, next: String, maxOverlap: Int = 400): String {
        if (previous.isBlank() || next.isBlank()) return normalize(next)
        val tail = normalize(previous).takeLast(maxOverlap)
        val head = normalize(next).take(maxOverlap)
        var best = 0
        for (length in minOf(tail.length, head.length) downTo 1) {
            if (tail.endsWith(head.substring(0, length))) {
                best = length
                break
            }
        }
        return normalize(next).removePrefix(head.substring(0, best)).trimStart()
    }
}
