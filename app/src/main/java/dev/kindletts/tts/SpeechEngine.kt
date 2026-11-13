package dev.kindletts.tts

interface SpeechEngine {
    val isReady: Boolean

    fun initialize(listener: SpeechListener)

    fun speak(text: String, utteranceId: String, rate: Float): Boolean

    fun stop()

    fun shutdown()
}

interface SpeechListener {
    fun onReady()
    fun onUnavailable(message: String)
    fun onStart(utteranceId: String)
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String, message: String)
}
