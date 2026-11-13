package dev.kindletts.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.kindletts.MainActivity
import dev.kindletts.R
import dev.kindletts.reader.CompositePageSource
import dev.kindletts.tts.AndroidSpeechEngine
import dev.kindletts.tts.PlaybackController
import dev.kindletts.tts.PlaybackStatus
import dev.kindletts.tts.PlaybackUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlaybackService : Service() {

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    lateinit var controller: PlaybackController
        private set

    private var isForeground = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    scope.launch { controller.pause() }
                }
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("PlaybackService", "created")
        controller = PlaybackController(
            engine = AndroidSpeechEngine(this),
            pageSource = CompositePageSource(),
            scope = scope,
        )
        createChannel()
        scope.launch {
            controller.state.collect { state ->
                updateWakeLock(state.status == PlaybackStatus.PLAYING)
                updateAudioFocus(state.status == PlaybackStatus.PLAYING)
                val notification = buildNotification(state)
                if (isForeground) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                startForeground(NOTIFICATION_ID, buildNotification(controller.state.value))
                isForeground = true
                if (controller.state.value.status == PlaybackStatus.PAUSED) {
                    controller.resume()
                } else {
                    controller.start()
                }
            }
            ACTION_PAUSE -> controller.pause()
            ACTION_TOGGLE -> {
                when (controller.state.value.status) {
                    PlaybackStatus.PLAYING -> controller.pause()
                    PlaybackStatus.PAUSED -> controller.resume()
                    PlaybackStatus.IDLE -> {
                        startForeground(NOTIFICATION_ID, buildNotification(controller.state.value))
                        isForeground = true
                        controller.start()
                    }
                }
            }
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller.shutdown()
        updateWakeLock(false)
        updateAudioFocus(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun stopPlayback() {
        controller.stop()
        isForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Kindle TTS playback controls"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: PlaybackUiState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when (state.status) {
            PlaybackStatus.PLAYING -> "Reading — page ${state.pageNumber.coerceAtLeast(1)}"
            PlaybackStatus.PAUSED -> state.error ?: state.message ?: "Paused"
            PlaybackStatus.IDLE -> state.error ?: "Ready"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Kindle TTS")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(state.status == PlaybackStatus.PLAYING)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                if (state.status == PlaybackStatus.PAUSED) {
                    android.R.drawable.ic_media_play
                } else {
                    android.R.drawable.ic_media_pause
                },
                if (state.status == PlaybackStatus.PAUSED) "Resume" else "Pause",
                toggleIntent,
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun updateWakeLock(playing: Boolean) {
        if (playing) {
            val lock = wakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "kindle-tts:playback",
            ).apply {
                setReferenceCounted(false)
                wakeLock = this
            }
            if (!lock.isHeld) lock.acquire()
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun updateAudioFocus(playing: Boolean) {
        if (playing) {
            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY = "dev.kindletts.action.PLAY"
        private const val ACTION_PAUSE = "dev.kindletts.action.PAUSE"
        private const val ACTION_TOGGLE = "dev.kindletts.action.TOGGLE"
        private const val ACTION_STOP = "dev.kindletts.action.STOP"

        fun play(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY),
            )
        }

        fun pause(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }
    }
}
