package dev.kindletts.reader

/** Supplies pages to playback without knowing how they are rendered. */
interface PageSource {
    /** The page currently shown in the reader. */
    suspend fun currentPage(): PageResult

    /** Advance one page and return its text, a boundary, or an error. */
    suspend fun nextPage(): PageResult
}

class ReaderPageSource : PageSource {
    override suspend fun currentPage(): PageResult {
        if (!ReaderSession.isAttached) return PageResult.Error("Reader closed. Reopen Kindle TTS to continue.")
        if (ReaderSession.sessionExpired.value) return PageResult.SignIn()
        return ReaderSession.extractPage()
    }

    override suspend fun nextPage(): PageResult {
        if (!ReaderSession.isAttached) return PageResult.Error("Reader closed. Reopen Kindle TTS to continue.")
        if (ReaderSession.sessionExpired.value) return PageResult.SignIn()
        val turn = ReaderSession.turnPage()
        return when {
            turn.strategy == "timeout" -> PageResult.Error("The reader did not respond to a page turn")
            turn.strategy == "no-content-frame" -> PageResult.NoContent
            !turn.changed -> PageResult.Boundary
            turn.text.isBlank() -> PageResult.NoContent
            else -> PageResult.Text(turn.text, ReaderSession.lastPageUrl.value, turn.strategy)
        }
    }
}
