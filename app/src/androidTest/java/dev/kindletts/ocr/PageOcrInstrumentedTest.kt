package dev.kindletts.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageOcrInstrumentedTest {

    @Test
    fun readsTextFromARenderedPageBitmap() = runBlocking {
        val bitmap = PageOcr.samplePage("The lighthouse keeper wrote every name.")
        val text = PageOcr.recognize(bitmap)
        android.util.Log.d("PageOcrTest", "recognized: $text")
        assertTrue("OCR did not read the page: $text", text.contains("lighthouse", ignoreCase = true))
        assertTrue(text.contains("name", ignoreCase = true))
    }
}
