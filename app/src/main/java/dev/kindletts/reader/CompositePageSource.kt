package dev.kindletts.reader

import dev.kindletts.ocr.OcrPageSource
import kotlinx.coroutines.delay

/**
 * Chooses the extraction path for the page in front of the user:
 * OCR for Kindle's image-rendered book pages, DOM text for text-rendered
 * pages and the local test fixtures.
 */
class CompositePageSource : PageSource {
    private val ocr = OcrPageSource()
    private val dom = ReaderPageSource()
    private var usingOcr = false

    override suspend fun currentPage(): PageResult {
        if (!ReaderSession.isAttached) return PageResult.Error("Reader closed. Reopen Kindle TTS to continue.")
        if (ReaderSession.sessionExpired.value) return PageResult.SignIn()

        var info = ReaderSession.pageInfo()
        var attempts = 0
        while ((info == null || info.images.isEmpty()) && attempts < 3) {
            attempts++
            delay(700)
            info = ReaderSession.pageInfo()
        }
        usingOcr = info != null && info.images.isNotEmpty()
        return if (usingOcr) ocr.currentPage() else dom.currentPage()
    }

    override suspend fun nextPage(): PageResult {
        if (!ReaderSession.isAttached) return PageResult.Error("Reader closed. Reopen Kindle TTS to continue.")
        if (ReaderSession.sessionExpired.value) return PageResult.SignIn()
        return if (usingOcr) ocr.nextPage() else dom.nextPage()
    }
}
