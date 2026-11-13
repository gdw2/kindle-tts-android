package dev.kindletts.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidSpeechEngine(context: Context) : SpeechEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initTimeout: Runnable? = null
    private var tts: TextToSpeech? = null
    private var listener: SpeechListener? = null
    private var initializing = false

    override var isReady: Boolean = false
        private set

    override fun initialize(listener: SpeechListener) {
        this.listener = listener
        if (isReady) {
            listener.onReady()
            return
        }
        if (initializing) return
        initializing = true

        val defaultEngine = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        Log.d("SpeechEngine", "initializing with default engine=$defaultEngine")
        val timeout = Runnable {
            if (!isReady) {
                Log.w("SpeechEngine", "initialization timed out")
                initializing = false
                listener.onUnavailable("Text-to-speech engine did not respond")
            }
        }.also { initTimeout = it }
        mainHandler.postDelayed(timeout, INIT_TIMEOUT_MS)

        val initListener = TextToSpeech.OnInitListener { status ->
            mainHandler.removeCallbacks(timeout)
            initializing = false
            if (status != TextToSpeech.SUCCESS) {
                listener.onUnavailable("Text-to-speech engine failed to initialize")
                return@OnInitListener
            }
            val engine = tts
            if (engine == null) {
                listener.onUnavailable("Text-to-speech engine is not available")
                return@OnInitListener
            }

            var languageResult = TextToSpeech.ERROR
            try {
                val currentVoice = engine.voice
                if (currentVoice != null) {
                    languageResult = TextToSpeech.SUCCESS
                    Log.d(
                        "SpeechEngine",
                        "using provider voice=${currentVoice.name} locale=${currentVoice.locale}",
                    )
                } else {
                    languageResult = engine.setLanguage(Locale.getDefault())
                }
            } catch (error: Exception) {
                Log.w("SpeechEngine", "voice selection failed: ${error.message}")
                languageResult = engine.setLanguage(Locale.getDefault())
            }

            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                listener.onUnavailable("No voice is installed for ${Locale.getDefault().displayName}")
                return@OnInitListener
            }

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceId?.let { listener.onStart(it) }
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { listener.onDone(it) }
                }

                override fun onError(utteranceId: String?) {
                    utteranceId?.let { listener.onError(it, "Speech engine error") }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { listener.onError(it, "Speech engine error $errorCode") }
                }
            })
            isReady = true
            val activeVoice = engine.voice
            Log.d(
                "SpeechEngine",
                "ready engine=$defaultEngine voice=${activeVoice?.name} locale=${activeVoice?.locale} language=$languageResult",
            )
            listener.onReady()
        }

        tts = try {
            TextToSpeech(appContext, initListener)
        } catch (error: Exception) {
            Log.w("SpeechEngine", "default engine construction failed: ${error.message}")
            listener.onUnavailable("Text-to-speech engine failed to initialize")
            null
        }
    }

    override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
        val engine = tts ?: return false
        if (!isReady) return false
        return engine.setSpeechRate(rate) == TextToSpeech.SUCCESS &&
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        isReady = false
        initTimeout?.let { mainHandler.removeCallbacks(it) }
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
    }
}
