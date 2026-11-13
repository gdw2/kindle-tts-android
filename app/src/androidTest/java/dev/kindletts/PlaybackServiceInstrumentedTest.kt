package dev.kindletts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.kindletts.playback.PlaybackService
import dev.kindletts.reader.ReaderSession
import dev.kindletts.tts.PlaybackStatus
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackServiceInstrumentedTest {

    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun serviceActionsWorkAndStopClearsPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scenario = ActivityScenario.launch(FixtureActivity::class.java)
        waitForContentFrame()

        var service: PlaybackService? = null
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? PlaybackService.LocalBinder)?.service
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        context.bindService(
            Intent(context, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        assertTrue("service did not bind", bound.await(10, TimeUnit.SECONDS))
        val controller = checkNotNull(service).controller

        try {
            context.startForegroundService(
                Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY),
            )
            assertTrue(
                "playback did not start: ${controller.state.value}",
                waitFor(15_000) { controller.state.value.status == PlaybackStatus.PLAYING },
            )

            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_PAUSE))
            assertTrue(waitFor(5_000) { controller.state.value.status == PlaybackStatus.PAUSED })

            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_TOGGLE))
            assertTrue(waitFor(5_000) { controller.state.value.status == PlaybackStatus.PLAYING })

            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val focusRequest = android.media.AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ).setOnAudioFocusChangeListener { }.build()
            audio.requestAudioFocus(focusRequest)
            assertTrue(
                "audio focus loss did not pause playback: ${controller.state.value}",
                waitFor(5_000) { controller.state.value.status == PlaybackStatus.PAUSED },
            )
            audio.abandonAudioFocusRequest(focusRequest)
            SystemClock.sleep(2_000)
            assertTrue(
                "playback resumed without the user",
                controller.state.value.status == PlaybackStatus.PAUSED,
            )

            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
            assertTrue(
                "stop did not clear playback: ${controller.state.value}",
                waitFor(5_000) { controller.state.value.status == PlaybackStatus.IDLE },
            )

            scenario.close()

            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
            assertTrue(waitFor(5_000) { controller.state.value.status == PlaybackStatus.IDLE })
        } finally {
            context.unbindService(connection)
        }
    }

    private fun waitForContentFrame() {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ReaderSession.contentFrameId.value != null) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Reader content frame never registered")
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(200)
        }
        return predicate()
    }

    private companion object {
        const val ACTION_PLAY = "dev.kindletts.action.PLAY"
        const val ACTION_PAUSE = "dev.kindletts.action.PAUSE"
        const val ACTION_TOGGLE = "dev.kindletts.action.TOGGLE"
        const val ACTION_STOP = "dev.kindletts.action.STOP"
    }
}
