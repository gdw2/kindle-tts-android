package dev.kindletts.ocr

import dev.kindletts.reader.PageImageInfo
import dev.kindletts.reader.PageInfo
import dev.kindletts.reader.PageResult
import dev.kindletts.reader.PageSource
import dev.kindletts.reader.ReaderSession
import kotlin.math.abs

/**
 * Reads Kindle's image-rendered pages.
 *
 * Kindle for Web draws each page as a full-page bitmap served from a blob URL.
 * The image is captured from inside the WebView (no OS screen capture), OCR'd
 * on device, and the text is handed to the existing playback queue. Advancing
 * scrolls the next page image to the top of the reader and, at a chapter end,
 * clicks the reader's next control; the visible page therefore leads the audio,
 * which is expected.
 */
class OcrPageSource : PageSource {
    private var currentSrc: String? = null
    private var lastLabel: String? = null
    private var remainingPages: Int? = null

    override suspend fun currentPage(): PageResult {
        if (ReaderSession.sessionExpired.value) return PageResult.SignIn()
        var info = ReaderSession.pageInfo()
        var attempts = 0
        while ((info == null || info.images.isEmpty()) && attempts < 3) {
            attempts++
            kotlinx.coroutines.delay(700)
            info = ReaderSession.pageInfo()
        }
        if (info == null) return PageResult.Error("Open a book page first")
        if (info.images.isEmpty()) {
            return PageResult.Error("The open page has no page image; open a book in Kindle for Web")
        }
        lastLabel = info.label
        remainingPages = pagesRemaining(info.label)
        val current = chooseCurrentPage(info.images) ?: return PageResult.NoContent
        currentSrc = current.src
        android.util.Log.d("OcrPageSource", "current index=${current.index} label=${info.label} images=${info.images.size}")
        return ocr(current)
    }

    override suspend fun nextPage(): PageResult {
        if (ReaderSession.sessionExpired.value) return PageResult.SignIn()
        if (remainingPages != null && remainingPages!! <= 0) return PageResult.Boundary
        if (isLastPage(lastLabel)) return PageResult.Boundary

        val before = currentSrc
        if (!ReaderSession.scrollNextPage()) {
            return PageResult.Error("The reader did not respond to a page turn")
        }
        var info: PageInfo? = null
        var current: PageImageInfo? = null
        var attempts = 0
        // The bridge falls back to a real page turn at a chapter end, and the next
        // chapter's page images take longer to load than a scroll within a chapter.
        while (attempts < 7) {
            attempts++
            kotlinx.coroutines.delay(if (attempts == 1) 500 else 700)
            info = ReaderSession.pageInfo()
            current = info?.let { chooseCurrentPage(it.images) }
            if (current != null && current.src != before) break
        }
        if (info == null) return PageResult.Error("The reader did not return a page")
        if (current == null || current.src == before) {
            return PageResult.Boundary
        }
        lastLabel = info.label
        currentSrc = current.src
        remainingPages?.let { remainingPages = (it - 1).coerceAtLeast(0) }
        android.util.Log.d("OcrPageSource", "next index=${current.index} label=${info.label} remaining=$remainingPages images=${info.images.size}")
        return ocr(current)
    }

    private suspend fun ocr(image: PageImageInfo): PageResult {
        val captured = ReaderSession.capturePageImage(image.index, image.src)
            ?: return PageResult.Error("Could not capture the page image")
        if (captured.src != image.src) {
            android.util.Log.w(
                "OcrPageSource",
                "page image changed: expected ${image.src.takeLast(8)} got ${captured.src.takeLast(8)}",
            )
        }
        return try {
            val text = PageOcr.recognizeJpegBase64(captured.base64)
            android.util.Log.d("OcrPageSource", "FULL index=${image.index} src=${captured.src.takeLast(8)} >>>$text<<<")
            if (text.isBlank()) {
                PageResult.NoContent
            } else {
                PageResult.Text(text, captured.src, "ocr")
            }
        } catch (error: Exception) {
            PageResult.Error("OCR failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    companion object {
        /**
         * The current page is the image whose top is at the viewport top; Kindle
         * keeps the visible page image flush with the content edge after a turn.
         */
        internal fun chooseCurrentPage(images: List<PageImageInfo>): PageImageInfo? {
            if (images.isEmpty()) return null
            val pages = images.filter { it.height > 200f }.ifEmpty { images }
            val flush = pages
                .filter { abs(it.y) <= 24f }
                .minByOrNull { abs(it.y) }
            if (flush != null) return flush
            val firstVisible = pages
                .filter { it.y >= 0f && it.y < 200f }
                .minByOrNull { it.y }
            return firstVisible ?: pages.maxByOrNull { it.width * it.height }
        }

        /** Pages left after the current one, when "Page N of M" is visible. */
        internal fun pagesRemaining(label: String?): Int? {
            val match = Regex("Page\\s+(\\d+)\\s+of\\s+(\\d+)").find(label ?: "") ?: return null
            return (match.groupValues[2].toIntOrNull() ?: return null) -
                (match.groupValues[1].toIntOrNull() ?: return null)
        }

        internal fun isLastPage(label: String?): Boolean {
            val match = Regex("Page\\s+(\\d+)\\s+of\\s+(\\d+)").find(label ?: "") ?: return false
            return match.groupValues[1] == match.groupValues[2]
        }
    }
}
