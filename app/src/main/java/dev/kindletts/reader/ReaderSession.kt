package dev.kindletts.reader

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

data class FrameInfo(
    val frameId: String,
    val url: String,
    val title: String,
    val textLength: Int,
    val pageImages: Int,
    val depth: Int,
    val isTop: Boolean,
) {
    val isSignIn: Boolean get() = SIGN_IN_PATTERN.containsMatchIn(url)
    val isExcluded: Boolean get() = EXCLUDED_PATTERN.containsMatchIn(url)
}

data class PageImageInfo(
    val index: Int,
    val y: Float,
    val height: Float,
    val width: Float,
    val naturalWidth: Int,
    val naturalHeight: Int,
    val src: String,
)

data class PageInfo(
    val label: String?,
    val url: String,
    val images: List<PageImageInfo>,
)

data class CapturedPage(
    val src: String,
    val base64: String,
    val width: Int,
    val height: Int,
)

sealed interface PageResult {
    data class Text(
        val text: String,
        val frameUrl: String,
        val strategy: String,
    ) : PageResult

    data object NoContent : PageResult

    data object Boundary : PageResult

    data class SignIn(val message: String = "Amazon sign-in required") : PageResult

    data class Error(val message: String) : PageResult
}

data class TurnResult(
    val changed: Boolean,
    val text: String,
    val strategy: String,
)

data class DiagnosticsSnapshot(
    val frames: List<FrameInfo>,
    val contentFrameId: String?,
    val lastPageText: String,
    val lastPageUrl: String,
    val lastTurn: String,
    val status: String,
)

private val EXCLUDED_PATTERN = Regex("kindle-library|/landing|signin|/ap/|/hz/", RegexOption.IGNORE_CASE)
private val SIGN_IN_PATTERN = Regex("signin|/ap/", RegexOption.IGNORE_CASE)

private const val MIN_CONTENT_LENGTH = 300
private const val EXTRACT_TIMEOUT_MS = 4_000L
private const val TURN_TIMEOUT_MS = 6_000L

/**
 * Process-scoped owner of the Kindle WebView. The playback service can keep asking for
 * pages while the Activity is stopped; if the Activity is destroyed the WebView is gone.
 */
object ReaderSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestIds = AtomicLong(0)
    private val commands = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private var webView: WebView? = null
    private var frameCounter = 0
    private var pendingTurn: String? = null
    private var manualFrameId: String? = null
    private val frameSeen = ConcurrentHashMap<String, Long>()

    var readerFrameId: String? = null
        private set

    private val _frames = MutableStateFlow<List<FrameInfo>>(emptyList())
    val frames: StateFlow<List<FrameInfo>> = _frames.asStateFlow()

    private val _contentFrameId = MutableStateFlow<String?>(null)
    val contentFrameId: StateFlow<String?> = _contentFrameId.asStateFlow()

    private val _lastPageText = MutableStateFlow("")
    val lastPageText: StateFlow<String> = _lastPageText.asStateFlow()

    private val _lastPageUrl = MutableStateFlow("")
    val lastPageUrl: StateFlow<String> = _lastPageUrl.asStateFlow()

    private val _lastTurn = MutableStateFlow("")
    val lastTurn: StateFlow<String> = _lastTurn.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    val isAttached: Boolean get() = webView != null

    fun attach(context: Context, view: WebView) {
        webView = view
        _frames.value = emptyList()
        frameSeen.clear()
        _contentFrameId.value = null
        readerFrameId = null
        _status.value = ""
        manualFrameId = null
        view.addJavascriptInterface(Bridge(), "KindleBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val script = context.assets.open("reader_bridge.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
        } else {
            _status.value = "WebView does not support all-frame script injection"
        }
    }

    fun load(url: String) {
        webView?.loadUrl(url)
    }

    fun reload() {
        webView?.reload()
    }

    fun selectFrame(frameId: String?) {
        manualFrameId = frameId
        _contentFrameId.value = frameId
    }

    fun snapshot(): DiagnosticsSnapshot = DiagnosticsSnapshot(
        frames = frames.value,
        contentFrameId = contentFrameId.value,
        lastPageText = lastPageText.value,
        lastPageUrl = lastPageUrl.value,
        lastTurn = lastTurn.value,
        status = status.value,
    )

    fun diagnosticsReport(): String = buildString {
        val state = snapshot()
        appendLine("Kindle TTS reader diagnostics")
        appendLine("contentFrame=${state.contentFrameId}")
        appendLine("status=${state.status}")
        appendLine("lastUrl=${state.lastPageUrl}")
        appendLine("lastTurn=${state.lastTurn}")
        appendLine("frames=${state.frames.size}")
        state.frames.forEach { frame ->
            appendLine(
                "frame ${frame.frameId} depth=${frame.depth} top=${frame.isTop} " +
                    "length=${frame.textLength} url=${frame.url}",
            )
        }
        appendLine("---- last page text ----")
        appendLine(state.lastPageText)
    }

    suspend fun probe() {
        sendCommand(type = "probe", target = "*", timeoutMs = 1_000)
    }

    suspend fun pageInfo(): PageInfo? {
        val target = readerFrameId ?: return null
        var response = sendCommand("pageInfo", target, timeoutMs = 8_000)
        if (response == null) {
            val fallback = _frames.value
                .filter { it.pageImages > 0 }
                .maxByOrNull { frameSeen[it.frameId] ?: 0L }
                ?.frameId
            if (fallback != null && fallback != target) {
                response = sendCommand("pageInfo", fallback, timeoutMs = 8_000)
            }
        }
        if (response == null) {
            Log.w("ReaderSession", "pageInfo timed out for frame $target")
            return null
        }
        val images = mutableListOf<PageImageInfo>()
        val array = response.optJSONArray("images")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                images += PageImageInfo(
                    index = item.optInt("index"),
                    y = item.optDouble("y").toFloat(),
                    height = item.optDouble("height").toFloat(),
                    width = item.optDouble("width").toFloat(),
                    naturalWidth = item.optInt("naturalWidth"),
                    naturalHeight = item.optInt("naturalHeight"),
                    src = item.optString("src"),
                )
            }
        }
        return PageInfo(
            label = response.optString("label").ifBlank { null },
            url = response.optString("url"),
            images = images,
        ).also {
            Log.d("ReaderSession", "pageInfo images=${images.size} label=${it.label} frame=$target")
        }
    }

    suspend fun capturePageImage(index: Int, src: String): CapturedPage? {
        val target = readerFrameId ?: return null
        for (attempt in 1..2) {
            val response = sendCommand(
                "pageImage",
                target,
                timeoutMs = 30_000,
                extra = mapOf("index" to index, "src" to src),
            )
            if (response == null) {
                Log.w("ReaderSession", "pageImage timed out (attempt $attempt)")
                continue
            }
            val error = response.optString("error")
            if (error.isNotBlank()) {
                Log.w("ReaderSession", "pageImage error: $error")
                _status.value = error
                return null
            }
            val data = response.optString("data")
            Log.d("ReaderSession", "pageImage index=$index src=${response.optString("src")} bytes=${data.length}")
            if (data.isBlank()) return null
            return CapturedPage(
                src = response.optString("src"),
                base64 = data,
                width = response.optInt("width"),
                height = response.optInt("height"),
            )
        }
        return null
    }

    /** Advance the image reader by scrolling the next page image to the viewport top. */
    suspend fun scrollNextPage(): Boolean {
        val target = readerFrameId ?: return false
        val response = sendCommand("scrollNext", target, timeoutMs = 5_000) ?: return false
        if (response.has("error")) {
            _status.value = response.optString("error")
            return false
        }
        delay(500)
        return true
    }

    private fun recomputeReaderFrame(current: List<FrameInfo>) {
        // Prefer the most recently reporting frame; after navigation the old book
        // document can otherwise win by having more page images than the new one.
        val withImages = current.filter { it.pageImages > 0 }
            .maxWithOrNull(
                compareBy<FrameInfo> { frameSeen[it.frameId] ?: 0L }
                    .thenBy { it.pageImages },
            )
        if (withImages != null) {
            readerFrameId = withImages.frameId
            _sessionExpired.value = false
            return
        }
        readerFrameId = current
            .filter { it.isTop && !it.isExcluded && !it.url.contains("kindle-library") }
            .maxByOrNull { frameSeen[it.frameId] ?: 0L }
            ?.frameId
    }

    suspend fun extractPage(): PageResult {
        if (sessionExpired.value) return PageResult.SignIn()
        val target = contentFrameId.value ?: return PageResult.NoContent
        val response = sendCommand(type = "extract", target = target, timeoutMs = EXTRACT_TIMEOUT_MS)
            ?: return PageResult.Error("Timed out waiting for the reader page")
        val text = response.optString("text")
        val url = response.optString("url")
        if (text.isBlank()) {
            _lastPageText.value = ""
            _lastPageUrl.value = url
            return PageResult.NoContent
        }
        _lastPageText.value = text
        _lastPageUrl.value = url
        Log.d("ReaderSession", "extract frame=$target chars=${text.length} strategy=${response.optString("strategy")}")
        return PageResult.Text(text, url, response.optString("strategy", "unknown"))
    }

    suspend fun turnPage(): TurnResult {
        val target = contentFrameId.value
            ?: return TurnResult(false, "", "no-content-frame")
        if (pendingTurn != null) return TurnResult(false, lastPageText.value, "busy")
        pendingTurn = target
        val response = sendCommand(type = "turn", target = target, timeoutMs = TURN_TIMEOUT_MS)
        pendingTurn = null
        if (response == null) {
            _lastTurn.value = "timeout"
            return TurnResult(false, lastPageText.value, "timeout")
        }
        val text = response.optString("text")
        val changed = response.optBoolean("changed")
        val strategy = response.optString("strategy", "unknown")
        _lastTurn.value = buildString {
            append(strategy)
            append(" changed=")
            append(changed)
        }
        Log.d("ReaderSession", "turn frame=$target strategy=$strategy changed=$changed chars=${text.length}")
        if (changed) {
            _lastPageText.value = text
            _lastPageUrl.value = response.optString("url", lastPageUrl.value)
        }
        return TurnResult(changed, text, strategy)
    }

    fun detach(view: WebView) {
        if (webView === view) {
            webView = null
            readerFrameId = null
            _contentFrameId.value = null
            _status.value = "WebView detached"
        }
    }

    private suspend fun sendCommand(
        type: String,
        target: String,
        timeoutMs: Long,
        extra: Map<String, Any?> = emptyMap(),
    ): JSONObject? {
        val requestId = "r${requestIds.incrementAndGet()}"
        val payload = JSONObject()
            .put("type", type)
            .put("requestId", requestId)
            .put("target", target)
        extra.forEach { (key, value) -> payload.put(key, value) }
        val deferred = CompletableDeferred<JSONObject>()
        pending[requestId] = deferred
        commands.getOrPut(target) { ConcurrentLinkedQueue() }.add(payload.toString())
        Log.d("ReaderSession", "command $type -> $target ($requestId)")
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
        pending.remove(requestId)
        if (result == null) {
            Log.w("ReaderSession", "command $type timed out ($requestId)")
            _status.value = "Command '$type' timed out"
        }
        return result
    }

    private fun poll(frameId: String): String {
        commands[frameId]?.poll()?.let { return it }
        return commands["*"]?.poll() ?: ""
    }

    private fun onMessage(raw: String) {
        val message = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        when (message.optString("type")) {
            "ready" -> onFrameReady(message)
            "extracted", "turned", "pageInfo", "pageImage", "scrolled", "error" -> {
                val requestId = message.optString("requestId")
                pending.remove(requestId)?.complete(message)
            }
        }
    }

    private fun onFrameReady(message: JSONObject) {
        val frame = FrameInfo(
            frameId = message.optString("frameId").ifBlank { "f${frameCounter++}" },
            url = message.optString("url"),
            title = message.optString("title"),
            textLength = message.optInt("textLength"),
            pageImages = message.optInt("pageImages"),
            depth = message.optInt("depth"),
            isTop = message.optBoolean("top"),
        )
        if (frame.frameId.isBlank()) return
        val now = System.currentTimeMillis()
        frameSeen[frame.frameId] = now
        val updated = (_frames.value.filterNot { it.frameId == frame.frameId } + frame)
            .sortedWith(compareByDescending<FrameInfo> { it.textLength }.thenBy { it.depth })
        // Drop frames that stopped reporting; old book documents keep their last ready forever.
        val live = updated.filter { now - (frameSeen[it.frameId] ?: 0L) < 10_000L }
        _frames.value = live
        Log.d("ReaderSession", "frame ${frame.frameId} depth=${frame.depth} chars=${frame.textLength} images=${frame.pageImages} url=${frame.url}")
        if (frame.isTop && frame.isSignIn) {
            _sessionExpired.value = true
            _status.value = "Amazon sign-in required"
        } else if (frame.isTop && !frame.isExcluded) {
            // A live reader or library top frame means the session is usable; hidden
            // sign-in iframes inside Amazon pages must not mark it expired.
            _sessionExpired.value = false
        }
        recomputeContentFrame(live)
        recomputeReaderFrame(live)
    }

    private fun recomputeContentFrame(current: List<FrameInfo>) {
        manualFrameId?.let { manual ->
            if (current.any { it.frameId == manual }) {
                _contentFrameId.value = manual
                return
            }
            manualFrameId = null
        }
        val best = current
            .filter { !it.isExcluded && it.textLength >= MIN_CONTENT_LENGTH }
            .maxByOrNull { it.textLength }
        _contentFrameId.value = best?.frameId
        if (best != null) {
            _sessionExpired.value = false
        }
        if (best?.isSignIn == true) {
            _status.value = "Amazon sign-in required"
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun postMessage(payload: String) {
            scope.launch { onMessage(payload) }
        }

        @JavascriptInterface
        fun poll(frameId: String): String = this@ReaderSession.poll(frameId)
    }
}
