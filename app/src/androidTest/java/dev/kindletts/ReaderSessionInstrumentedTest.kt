package dev.kindletts

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kindletts.reader.PageResult
import dev.kindletts.reader.ReaderSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSessionInstrumentedTest {

    @Test
    fun fixturePageIsExtractedAndTurnedOncePerPage() {
        ActivityScenario.launch(FixtureActivity::class.java).use {
            waitForContentFrame()

            val first = runBlocking { ReaderSession.extractPage() }
            assertTrue("expected a page, got $first", first is PageResult.Text)
            val firstText = (first as PageResult.Text).text
            assertTrue(firstText.contains("lighthouse keeper"))
            assertTrue(firstText.contains("patient hand"))

            val turn = runBlocking { ReaderSession.turnPage() }
            assertTrue("expected the fixture to advance", turn.changed)
            assertTrue(turn.text.contains("yellow crate"))

            val second = runBlocking { ReaderSession.extractPage() }
            assertTrue(second is PageResult.Text)
            assertTrue((second as PageResult.Text).text.contains("ledger gained a column"))

            val boundary = runBlocking { ReaderSession.turnPage() }
            assertFalse("the last fixture page must report a boundary", boundary.changed)
        }
    }

    @Test
    fun consecutiveTurnsAdvanceThenReportBoundary() {
        ActivityScenario.launch(FixtureActivity::class.java).use {
            waitForContentFrame()
            runBlocking { ReaderSession.extractPage() }

            val first = runBlocking { ReaderSession.turnPage() }
            assertTrue(first.changed)
            assertTrue(first.text.contains("yellow crate"))

            val second = runBlocking { ReaderSession.turnPage() }
            assertFalse("the last fixture page must report a boundary", second.changed)
        }
    }

    private fun waitForContentFrame() {
        SystemClock.sleep(500)
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ReaderSession.contentFrameId.value != null) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(100)
        }
        throw AssertionError("Reader content frame never registered: ${ReaderSession.diagnosticsReport()}")
    }
}
