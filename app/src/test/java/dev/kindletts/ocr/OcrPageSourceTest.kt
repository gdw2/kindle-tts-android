package dev.kindletts.ocr

import dev.kindletts.reader.PageImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPageSourceTest {

    private fun image(index: Int, y: Float, src: String = "blob:$index") = PageImageInfo(
        index = index,
        y = y,
        height = 800f,
        width = 412f,
        naturalWidth = 1081,
        naturalHeight = 2210,
        src = src,
    )

    @Test
    fun choosesThePageFlushWithTheViewportTop() {
        val images = listOf(image(0, -800f), image(1, 0f), image(2, 788f))
        assertEquals(1, OcrPageSource.chooseCurrentPage(images)?.index)
    }

    @Test
    fun choosesTheFirstVisiblePageWhenTheTopIsSlightlyOffset() {
        val images = listOf(image(0, -900f), image(1, 4f), image(2, 792f))
        assertEquals(1, OcrPageSource.chooseCurrentPage(images)?.index)
    }

    @Test
    fun noImagesMeansNoPage() {
        assertNull(OcrPageSource.chooseCurrentPage(emptyList()))
    }

    @Test
    fun recognisesTheLastPageLabel() {
        assertTrue(OcrPageSource.isLastPage("Page 111 of 111"))
        assertTrue(OcrPageSource.isLastPage("Page 4 of 4 ● 100%"))
        assertFalse(OcrPageSource.isLastPage("Page 4 of 111"))
        assertFalse(OcrPageSource.isLastPage(null))
    }

    @Test
    fun countsPagesRemaining() {
        assertEquals(105, OcrPageSource.pagesRemaining("Page 6 of 111"))
        assertEquals(0, OcrPageSource.pagesRemaining("Page 111 of 111"))
        assertNull(OcrPageSource.pagesRemaining(null))
        assertNull(OcrPageSource.pagesRemaining("Page 6"))
    }
}
