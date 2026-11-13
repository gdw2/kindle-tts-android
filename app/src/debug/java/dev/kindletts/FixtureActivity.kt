package dev.kindletts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.kindletts.reader.FIXTURE_URL
import dev.kindletts.reader.KindleWebView
import dev.kindletts.ui.theme.KindleTtsTheme

/** Debug-only host used by the instrumented reader tests. */
class FixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KindleTtsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KindleWebView(modifier = Modifier.fillMaxSize(), initialUrl = FIXTURE_URL)
                }
            }
        }
    }
}
