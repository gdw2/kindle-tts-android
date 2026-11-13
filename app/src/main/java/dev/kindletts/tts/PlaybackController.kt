package dev.kindletts.tts

import android.util.Log
import dev.kindletts.reader.PageResult
import dev.kindletts.reader.PageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PlaybackStatus { IDLE, PLAYING, PAUSED }

data class PlaybackUiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val rate: Float = 1f,
    val currentText: String? = null,
    val pageNumber: Int = 0,
    val error: String? = null,
    val message: String? = null,
)

/**
 * Owns the speech queue. It pulls pages ahead of playback, so audio never depends
 * on the visible page, and it pauses cleanly at a chapter boundary or on errors.
 *
 * All methods run on the supplied [scope] (the main thread in the app).
 */
class PlaybackController(
    private val engine: SpeechEngine,
    private val pageSource: PageSource,
    private val scope: CoroutineScope,
    private val maxChars: Int = 350,
) {
    companion object {
        const val BUFFER_TARGET = 6
        const val IN_FLIGHT_LIMIT = 2
    }

    private data class Utterance(val text: String)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val ready = ArrayDeque<Utterance>()
    private val active = LinkedHashMap<String, Utterance>()
    private var lastPageText = ""
    private var heldTail: String? = null
    private var boundaryReached = false
    private var pendingError: String? = null
    private var fetchJob: Job? = null
    private var engineReady = false
    private var wantPlaying = false
    private var nextUtteranceId = 0L
    private var playbackEpoch = 0L
    private var rate = 1f
    private var pagesLoaded = 0

    init {
        engine.initialize(object : SpeechListener {
            override fun onReady() {
                scope.launch {
                    engineReady = true
                    if (wantPlaying) pump()
                }
            }

            override fun onUnavailable(message: String) {
                scope.launch {
                    engineReady = false
                    wantPlaying = false
                    _state.update {
                        it.copy(status = PlaybackStatus.IDLE, error = message, message = null)
                    }
                }
            }

            override fun onStart(utteranceId: String) {
                scope.launch {
                    val utterance = active[utteranceId] ?: return@launch
                    _state.update { it.copy(currentText = utterance.text) }
                }
            }

            override fun onDone(utteranceId: String) {
                scope.launch {
                    if (active.remove(utteranceId) == null) return@launch
                    _state.update { it.copy(currentText = active.values.firstOrNull()?.text) }
                    pump()
                }
            }

            override fun onError(utteranceId: String, message: String) {
                scope.launch {
                    if (active.remove(utteranceId) == null) return@launch
                    pauseInternal(error = message)
                }
            }
        })
    }

    fun start() {
        if (_state.value.status != PlaybackStatus.IDLE) return
        val epoch = ++playbackEpoch
        scope.launch { beginFromCurrentPage(epoch) }
    }

    fun pause() {
        // Cancel any in-flight resume; audio focus loss must win even if the
        // controller has not reached PLAYING yet.
        playbackEpoch++
        if (_state.value.status == PlaybackStatus.PLAYING) {
            pauseInternal()
        }
    }

    fun resume() {
        if (_state.value.status != PlaybackStatus.PAUSED) return
        playbackEpoch++
        val epoch = playbackEpoch
        scope.launch {
            if (epoch != playbackEpoch) return@launch
            val retryFromPage = boundaryReached || pendingError != null || _state.value.error != null
            pendingError = null
            if (retryFromPage) {
                beginFromCurrentPage(epoch)
                return@launch
            }
            wantPlaying = true
            _state.update { it.copy(status = PlaybackStatus.PLAYING, error = null, message = null) }
            pump()
        }
    }

    fun stop() {
        playbackEpoch++
        wantPlaying = false
        fetchJob?.cancel()
        fetchJob = null
        engine.stop()
        active.clear()
        ready.clear()
        boundaryReached = false
        pendingError = null
        lastPageText = ""
        heldTail = null
        pagesLoaded = 0
        _state.update {
            PlaybackUiState(rate = rate)
        }
    }

    fun setRate(value: Float) {
        rate = value.coerceIn(0.5f, 3f)
        _state.update { it.copy(rate = rate) }
        if (wantPlaying) {
            engine.stop()
            requeueActive()
            pump()
        }
    }

    fun shutdown() {
        wantPlaying = false
        engine.stop()
        engine.shutdown()
    }

    private suspend fun beginFromCurrentPage(epoch: Long) {
        resetQueue()
        if (epoch != playbackEpoch) return
        wantPlaying = true
        _state.update {
            it.copy(
                status = PlaybackStatus.PLAYING,
                error = null,
                message = "Loading page…",
                currentText = null,
                pageNumber = 0,
            )
        }
        val page = pageSource.currentPage()
        if (epoch != playbackEpoch) return
        when (page) {
            is PageResult.Text -> {
                val added = appendPage(
                    page.text,
                    countPage = true,
                    stripDuplicate = page.strategy != "ocr",
                )
                if (added == 0) {
                    fail("No readable text on this page")
                    return
                }
                pump()
            }
            PageResult.NoContent -> fail("No readable page is open")
            PageResult.Boundary -> fail("No readable page is open")
            is PageResult.SignIn -> fail(page.message)
            is PageResult.Error -> fail(page.message)
        }
    }

    private fun pump() {
        if (!wantPlaying || !engineReady) return

        while (active.size < IN_FLIGHT_LIMIT && ready.isNotEmpty()) {
            val utterance = ready.removeFirst()
            val id = "u${nextUtteranceId++}"
            active[id] = utterance
            if (!engine.speak(utterance.text, id, rate)) {
                active.remove(id)
                pauseInternal(error = "Speech engine rejected the utterance")
                return
            }
            _state.update { it.copy(currentText = active.values.firstOrNull()?.text) }
        }

        if (active.isEmpty() && ready.isEmpty()) {
            pendingError?.let {
                pauseInternal(error = it)
                return
            }
            if (boundaryReached) {
                pauseInternal(message = "End of chapter. Turn the page, then press Play.")
                return
            }
        }
        ensureBuffered()
    }

    private fun ensureBuffered() {
        if (!wantPlaying || boundaryReached || pendingError != null) return
        if (fetchJob?.isActive == true) return
        if (ready.size + active.size >= BUFFER_TARGET) return

        fetchJob = scope.launch {
            when (val result = pageSource.nextPage()) {
                is PageResult.Text -> {
                    val added = appendPage(
                        result.text,
                        countPage = true,
                        stripDuplicate = result.strategy != "ocr",
                    )
                    if (added == 0) {
                        flushHeldTail()
                        boundaryReached = true
                        _state.update { it.copy(message = "End of chapter") }
                    }
                    pump()
                }
                PageResult.Boundary -> {
                    flushHeldTail()
                    boundaryReached = true
                    _state.update { it.copy(message = "End of chapter") }
                    pump()
                }
                is PageResult.SignIn -> {
                    flushHeldTail()
                    pendingError = result.message
                    pump()
                }
                PageResult.NoContent -> {
                    flushHeldTail()
                    pendingError = "No more readable text on this page"
                    pump()
                }
                is PageResult.Error -> {
                    flushHeldTail()
                    pendingError = result.message
                    pump()
                }
            }
        }
    }

    private fun appendPage(text: String, countPage: Boolean, stripDuplicate: Boolean = true): Int {
        val previous = lastPageText
        var remainder = if (stripDuplicate) {
            TextNormalizer.stripOverlap(previous, text)
        } else {
            TextNormalizer.normalize(text)
        }
        val holdTail = !stripDuplicate
        if (holdTail) {
            heldTail?.let {
                remainder = "$it $remainder"
                heldTail = null
            }
        }
        lastPageText = text
        val sentences = TextNormalizer.splitSentences(remainder, maxChars).toMutableList()
        var held: String? = null
        if (holdTail && sentences.isNotEmpty() && !endsWithSentencePunctuation(sentences.last())) {
            held = sentences.removeAt(sentences.lastIndex)
            heldTail = held
        }
        Log.d(
            "PlaybackController",
            "append raw=${text.length} kept=${remainder.length} stripped=${text.length - remainder.length} " +
                "held=${held?.takeLast(50)?.replace('\n', ' ')} " +
                "prevTail=${previous.takeLast(60).replace('\n', ' ')} nextHead=${text.take(60).replace('\n', ' ')}",
        )
        sentences.forEach { ready.addLast(Utterance(it)) }
        if (sentences.isNotEmpty()) {
            if (countPage) {
                pagesLoaded++
                _state.update { it.copy(pageNumber = pagesLoaded) }
            }
            _state.update { it.copy(message = null) }
        }
        return sentences.size + if (held != null) 1 else 0
    }

    private fun endsWithSentencePunctuation(text: String): Boolean {
        var end = text.length
        while (end > 0 && text[end - 1] in "\"')]}»”’") end--
        if (end == 0) return true
        return text[end - 1] in ".!?…"
    }

    private fun flushHeldTail() {
        val tail = heldTail ?: return
        heldTail = null
        Log.d("PlaybackController", "flush held tail=${tail.takeLast(80).replace('\n', ' ')}")
        ready.addLast(Utterance(tail))
    }

    private fun pauseInternal(error: String? = null, message: String? = null) {
        playbackEpoch++
        wantPlaying = false
        engine.stop()
        requeueActive()
        _state.update {
            it.copy(
                status = PlaybackStatus.PAUSED,
                currentText = ready.firstOrNull()?.text,
                error = error ?: it.error,
                message = if (error != null) null else (message ?: it.message),
            )
        }
    }

    private fun requeueActive() {
        if (active.isEmpty()) return
        active.values.toList().asReversed().forEach { ready.addFirst(it) }
        active.clear()
    }

    private fun resetQueue() {
        fetchJob?.cancel()
        fetchJob = null
        ready.clear()
        active.clear()
        lastPageText = ""
        heldTail = null
        boundaryReached = false
        pendingError = null
        pagesLoaded = 0
    }

    private fun fail(message: String) {
        playbackEpoch++
        wantPlaying = false
        engine.stop()
        active.clear()
        ready.clear()
        heldTail = null
        _state.update {
            it.copy(
                status = PlaybackStatus.IDLE,
                error = message,
                message = null,
                currentText = null,
                pageNumber = 0,
            )
        }
    }
}
