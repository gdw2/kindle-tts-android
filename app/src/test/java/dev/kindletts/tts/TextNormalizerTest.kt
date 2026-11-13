package dev.kindletts.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun joinsHyphenatedLineBreaks() {
        val text = TextNormalizer.normalize("The keeper wrote in a green led-\nger every night.")
        assertEquals("The keeper wrote in a green ledger every night.", text)
    }

    @Test
    fun collapsesWhitespaceAndNbsp() {
        val text = TextNormalizer.normalize("  one\u00a0 two \t three  \n\n\n  four  ")
        assertEquals("one two three\n\nfour", text)
    }

    @Test
    fun splitsSentencesAtPunctuation() {
        val sentences = TextNormalizer.splitSentences(
            "The sea was flat. Was it? Yes! Then the wind returned…",
        )
        assertEquals(
            listOf("The sea was flat.", "Was it?", "Yes!", "Then the wind returned…"),
            sentences,
        )
    }

    @Test
    fun doesNotSplitCommonAbbreviations() {
        val sentences = TextNormalizer.splitSentences("Dr. Bell met Mr. Finch at 5 p.m. Sharp.")
        assertEquals(listOf("Dr. Bell met Mr. Finch at 5 p.m. Sharp."), sentences)
    }

    @Test
    fun keepsParagraphsAsSeparateSentences() {
        val sentences = TextNormalizer.splitSentences("First paragraph\n\nSecond paragraph")
        assertEquals(listOf("First paragraph", "Second paragraph"), sentences)
    }

    @Test
    fun joinsSingleLineBreaksButKeepsParagraphs() {
        val text = TextNormalizer.normalize("Paul kicked the ball\nalong the road.\n\nHe smiled.")
        assertEquals("Paul kicked the ball along the road.\n\nHe smiled.", text)
    }

    @Test
    fun splitsLongSentencesUnderTheLimit() {
        val long = (1..40).joinToString(" ") { "word$it" } + "."
        val sentences = TextNormalizer.splitSentences(long, maxChars = 80)
        assertTrue(sentences.size > 1)
        assertTrue(sentences.all { it.length <= 80 })
    }

    @Test
    fun stripsTextRepeatedAtPageBoundaries() {
        val previous = "The keeper counted boats. The storm pulled the tiles loose."
        val next = "The storm pulled the tiles loose. By morning the sea was tin."
        assertEquals("By morning the sea was tin.", TextNormalizer.stripOverlap(previous, next))
    }

    @Test
    fun leavesNonOverlappingPagesAlone() {
        val next = "By morning the sea was tin."
        assertEquals(next, TextNormalizer.stripOverlap("A different page.", next))
    }
}
