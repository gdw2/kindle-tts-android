package dev.kindletts.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

const val KINDLE_URL = "https://read.amazon.com/"
const val FIXTURE_URL = "https://appassets.androidplatform.net/assets/fixture/reader.html"
const val FIXTURE_LONG_URL = "https://appassets.androidplatform.net/assets/fixture/long-reader.html"
const val FIXTURE_CHAPTER_URL = "https://appassets.androidplatform.net/assets/fixture/chapter.html"

private val AMAZON_HOSTS = setOf(
    "amazon.com",
    "amazon.co.uk",
    "amazon.ca",
    "amazon.com.au",
    "amazon.in",
    "amazon.co.jp",
    "amazon.de",
    "amazon.fr",
    "amazon.es",
    "amazon.it",
    "amazon.com.br",
    "amazon.com.mx",
    "amazon.nl",
)

internal fun Uri.isAmazonHost(): Boolean {
    val host = host.orEmpty()
    return AMAZON_HOSTS.any { host == it || host.endsWith(".$it") } ||
        host.endsWith(".media-amazon.com")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KindleWebView(
    modifier: Modifier = Modifier,
    initialUrl: String = KINDLE_URL,
    onWebViewReady: (WebView) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()
                webViewClient = AmazonWebViewClient(context, assetLoader)
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d("WebViewConsole", "${message.message()} @${message.lineNumber()}")
                        return true
                    }
                }
                ReaderSession.attach(context, this)

                // Kindle for Web computes its layout from the viewport; loading before the
                // WebView has a real size leaves its CSS viewport height at zero.
                var loaded = false
                fun loadWhenSized() {
                    if (loaded || width <= 0 || height <= 0) return
                    loaded = true
                    loadUrl(initialUrl)
                    onWebViewReady(this)
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    post { loadWhenSized() }
                }
                post { loadWhenSized() }
            }
        },
        onRelease = {
            ReaderSession.detach(it)
            it.destroy()
        },
    )
}

private class AmazonWebViewClient(
    private val context: Context,
    private val assetLoader: WebViewAssetLoader,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return assetLoader.shouldInterceptRequest(request.url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme != "http" && url.scheme != "https") return true
        if (url.isAmazonHost() || url.host == "appassets.androidplatform.net") return false
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
        return true
    }
}
