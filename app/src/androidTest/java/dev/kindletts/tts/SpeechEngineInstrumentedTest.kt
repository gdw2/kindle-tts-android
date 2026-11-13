package dev.kindletts.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SpeechEngineInstrumentedTest {

    @Test
    fun realEngineSpeaksAndFinishes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AndroidSpeechEngine(context)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val unavailable = CountDownLatch(1)
        val error = AtomicReference<String?>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.initialize(object : SpeechListener {
                override fun onReady() {
                    engine.speak("Hello from the speech engine test.", "smoke", 1f)
                }

                override fun onUnavailable(message: String) {
                    error.set(message)
                    unavailable.countDown()
                }

                override fun onStart(utteranceId: String) {
                    started.countDown()
                }

                override fun onDone(utteranceId: String) {
                    done.countDown()
                }

                override fun onError(utteranceId: String, message: String) {
                    error.set(message)
                    unavailable.countDown()
                }
            })
        }

        val unavailableFirst = unavailable.await(15, TimeUnit.SECONDS)
        assumeTrue("No usable TTS engine on this device: ${error.get()}", !unavailableFirst)

        assertTrue("speech never started", started.await(20, TimeUnit.SECONDS))
        assertTrue("speech never finished", done.await(30, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.shutdown() }
    }
}
