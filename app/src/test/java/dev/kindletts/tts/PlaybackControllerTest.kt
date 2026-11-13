package dev.kindletts.tts

import dev.kindletts.reader.PageResult
import dev.kindletts.reader.PageSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {

    @Test
    fun startSpeaksPageAndReadsAhead() = runTest {
        val engine = FakeEngine()
        val source = FakeSource(
            current = page("One. Two. Three."),
            next = mutableListOf(page("Four. Five. Six.")),
        )
        val controller = PlaybackController(engine, source, this)

        controller.start()
        runCurrent()

        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(2, engine.spoken.size)
        assertEquals("One.", controller.state.value.currentText)
        assertEquals(1, source.nextCalls)
        assertEquals(2, controller.state.value.pageNumber)

        engine.finish(engine.spoken[0].id)
        runCurrent()
        assertEquals("Two.", controller.state.value.currentText)
    }

    @Test
    fun pauseStopsAndResumeRepeatsTheCurrentSentence() = runTest {
        val engine = FakeEngine()
        val source = FakeSource(page("One. Two. Three."), mutableListOf(page("Four.")))
        val controller = PlaybackController(engine, source, this)
        controller.start()
        runCurrent()

        controller.pause()
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        assertEquals(1, engine.stops)

        val spokenBefore = engine.spoken.size
        controller.resume()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        assertTrue(engine.spoken.size > spokenBefore)
        assertEquals("One.", engine.spoken.drop(spokenBefore).first().text)
    }

    @Test
    fun rateChangeReSpeaksTheRemainder() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(
            engine,
            FakeSource(page("One. Two. Three."), mutableListOf(page("Four."))),
            this,
        )
        controller.start()
        runCurrent()
        val spokenBefore = engine.spoken.size

        controller.setRate(1.5f)
        runCurrent()

        assertEquals(1.5f, controller.state.value.rate)
        val spokenAfter = engine.spoken.drop(spokenBefore)
        assertTrue(spokenAfter.isNotEmpty())
        assertEquals("One.", spokenAfter.first().text)
        assertTrue(spokenAfter.all { it.rate == 1.5f })
    }

    @Test
    fun boundaryPausesAfterTheBufferDrains() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(
            engine,
            FakeSource(page("One. Two."), mutableListOf(PageResult.Boundary)),
            this,
        )
        controller.start()
        runCurrent()
        drain(engine, controller)

        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message.orEmpty().contains("End of chapter"))
        assertEquals(listOf("One.", "Two."), engine.spoken.map { it.text })
    }

    @Test
    fun overlappingTextIsSpokenOnce() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(
            engine,
            FakeSource(
                page("Alpha beta. Gamma delta."),
                mutableListOf(page("Gamma delta. Epsilon zeta."), PageResult.Boundary),
            ),
            this,
        )
        controller.start()
        runCurrent()
        drain(engine, controller)

        val texts = engine.spoken.map { it.text }
        assertEquals(listOf("Alpha beta.", "Gamma delta.", "Epsilon zeta."), texts)
        assertEquals(1, texts.count { it == "Gamma delta." })
    }

    @Test
    fun pageErrorPausesAfterBufferedTextIsSpoken() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(
            engine,
            FakeSource(page("One. Two."), mutableListOf(PageResult.Error("boom"))),
            this,
        )
        controller.start()
        runCurrent()
        drain(engine, controller)

        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        assertEquals("boom", controller.state.value.error)
        assertEquals(listOf("One.", "Two."), engine.spoken.map { it.text })
    }

    @Test
    fun unavailableEngineIsReportedWithoutStarting() = runTest {
        val engine = FakeEngine(available = false)
        val controller = PlaybackController(engine, FakeSource(page("One.")), this)
        runCurrent()

        assertEquals(PlaybackStatus.IDLE, controller.state.value.status)
        assertTrue(controller.state.value.error.orEmpty().isNotBlank())
        assertFalse(engine.spoken.isNotEmpty())
    }

    @Test
    fun repeatedPageTextStopsAtBoundaryInsteadOfLooping() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(engine, RepeatingSource("One. Two."), this)
        controller.start()
        runCurrent()
        drain(engine, controller)

        assertEquals(PlaybackStatus.PAUSED, controller.state.value.status)
        assertTrue(controller.state.value.message.orEmpty().contains("End of chapter"))
        assertEquals(1, controller.state.value.pageNumber)
    }

    @Test
    fun incompleteOcrTailIsMergedWithTheNextPage() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(
            engine,
            FakeSource(ocrPage("Alpha beta gamma"), mutableListOf(ocrPage("delta epsilon. Zeta."))),
            this,
        )
        controller.start()
        runCurrent()

        val texts = engine.spoken.map { it.text }
        assertTrue("expected a merged sentence, got $texts", texts.contains("Alpha beta gamma delta epsilon."))
        assertTrue("tail must not be spoken alone", texts.none { it == "Alpha beta gamma" })
    }

    @Test
    fun incompleteOcrTailIsFlushedAtTheBoundary() = runTest {
        val engine = FakeEngine()
        val controller = PlaybackController(
            engine,
            FakeSource(ocrPage("Alpha beta gamma"), mutableListOf(PageResult.Boundary)),
            this,
        )
        controller.start()
        runCurrent()
        drain(engine, controller)

        assertTrue(engine.spoken.map { it.text }.contains("Alpha beta gamma"))
        assertTrue(controller.state.value.message.orEmpty().contains("End of chapter"))
    }

    private fun TestScope.drain(engine: FakeEngine, controller: PlaybackController) {
        val finished = mutableSetOf<String>()
        repeat(50) {
            runCurrent()
            val next = engine.spoken.map { it.id }.firstOrNull { it !in finished }
            if (next == null) {
                runCurrent()
                return
            }
            finished += next
            engine.finish(next)
            runCurrent()
        }
    }

    private fun page(text: String) = PageResult.Text(text, "fixture", "test")

    private fun ocrPage(text: String) = PageResult.Text(text, "fixture", "ocr")

    private class FakeEngine(available: Boolean = true) : SpeechEngine {
        data class Spoken(val id: String, val text: String, val rate: Float)

        val spoken = mutableListOf<Spoken>()
        private val available = available
        private var listener: SpeechListener? = null
        var stops = 0

        override var isReady = false
            private set

        override fun initialize(listener: SpeechListener) {
            this.listener = listener
            if (available) {
                isReady = true
                listener.onReady()
            } else {
                listener.onUnavailable("No speech engine")
            }
        }

        override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
            if (!isReady) return false
            spoken += Spoken(utteranceId, text, rate)
            return true
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            isReady = false
        }

        fun finish(id: String) {
            listener?.onDone(id)
        }
    }

    private class FakeSource(
        private val current: PageResult,
        private val next: MutableList<PageResult> = mutableListOf(),
    ) : PageSource {
        var nextCalls = 0
            private set

        override suspend fun currentPage(): PageResult = current

        override suspend fun nextPage(): PageResult {
            nextCalls++
            return if (next.isEmpty()) PageResult.Boundary else next.removeAt(0)
        }
    }

    private class RepeatingSource(private val text: String) : PageSource {
        override suspend fun currentPage(): PageResult = PageResult.Text(text, "fixture", "test")

        override suspend fun nextPage(): PageResult = PageResult.Text(text, "fixture", "test")
    }
}
