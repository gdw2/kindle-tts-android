# Kindle for Web integration

Kindle for Web renders book text inside nested iframes and changes its DOM without notice. All integration code is deliberately concentrated in one injected script plus one process-scoped session, so a repair is a small, local change.

## Files

- `app/src/main/assets/reader_bridge.js` — the injected script. All selectors, URL exclusions, page-image capture, and page-turn strategies live here.
- `app/src/main/java/dev/kindletts/reader/ReaderSession.kt` — attaches the script, tracks frames, sends commands, captures page images, and swipes.
- `app/src/main/java/dev/kindletts/reader/PageSource.kt` — the DOM-text fallback.
- `app/src/main/java/dev/kindletts/ocr/OcrPageSource.kt` — page-image selection, OCR, preloaded pages, and boundaries.
- `app/src/main/java/dev/kindletts/ocr/PageOcr.kt` — ML Kit on-device text recognition.
- `app/src/main/java/dev/kindletts/ui/DiagnosticsDialog.kt` — the debug repair tool.

## Injection

`ReaderSession.attach()` calls `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))`, so the script runs in every frame, including cross-origin content frames. If the installed WebView does not support document-start scripts, the session reports that in the diagnostics status; it does not silently fall back.

The script installs once per frame, generates a `frameId`, reports `ready` immediately and every 2.5 seconds (URL, title, visible text length, iframe depth), and polls the native bridge every 120 ms.

## Bridge protocol

JavaScript to native: `KindleBridge.postMessage(jsonString)`.

```json
{ "type": "ready", "frameId": "f...", "url": "...", "title": "...",
  "textLength": 317, "depth": 1, "top": false }
{ "type": "extracted", "requestId": "r7", "frameId": "f...",
  "url": "...", "strategy": "innerText", "text": "..." }
{ "type": "turned", "requestId": "r8", "frameId": "f...",
  "url": "...", "strategy": "#kr-next", "changed": true, "text": "..." }
```

Native to JavaScript: the target frame calls `KindleBridge.poll(frameId)` and receives one queued command or an empty string.

```json
{ "type": "extract", "requestId": "r7", "target": "f..." }
{ "type": "turn",    "requestId": "r8", "target": "f..." }
{ "type": "pageInfo", "requestId": "r9", "target": "f..." }
{ "type": "pageImage", "requestId": "r10", "target": "f...", "index": 4 }
{ "type": "probe",   "requestId": "r11", "target": "*" }
```

Commands are delivered only to the selected frame (`target`), except `probe`, which any frame may answer. Native timeouts are 4 s for DOM extraction, 8 s for page info, and 30 s for a page image.

`pageInfo` returns the sorted page images with each image's viewport top, size, natural size, and `blob:` source, plus the "Page N of M" label when visible. `pageImage` draws the indexed image to a canvas at natural size and returns a JPEG base64 payload; native OCRs it.

## Choosing the content frame

`ReaderSession` picks the non-excluded frame with the largest visible text length (at least 300 characters) for DOM extraction, and separately tracks a reader frame by the number of `.kg-full-page-img` images it reports. Excluded URL patterns are:

```
kindle-library  /landing  signin  /ap/  /hz/
```

Depth is reported but not used for selection. In the diagnostics screen you can override the DOM content frame with **Use** on any frame; the override lasts until the WebView detaches.

## Extraction

Kindle for Web's current reader renders each page as a full-page bitmap served from `blob:` image URLs; the visible book text is not in the DOM, open shadow roots, JavaScript globals, or storage. The DOM extractor below is therefore a fallback for text-rendered pages and the local fixtures; the primary path is OCR.

### OCR pipeline

1. `OcrPageSource` asks the reader frame for `pageInfo`.
2. It picks the image whose top is flush with the viewport (the current page).
3. For that page, `pageImage` returns a JPEG; native decodes it and ML Kit's bundled text recognizer extracts the text.
4. The text goes through the normal sentence queue. Single OCR line breaks are joined into spaces; blank lines remain paragraph breaks. If a page ends mid-sentence, the trailing fragment is held and merged with the next page (or flushed at the end of the book).
5. When more text is needed, `scrollNext` scrolls the next page image to the top of the reader, waits, re-reads `pageInfo`, and OCRs the new page. The visible page therefore leads the audio by the read-ahead buffer.
6. `Page N of M` is used as a last-page signal when it is visible; a scroll that leaves the same current image also means the end of the book.

The capture happens inside the WebView, so it does not need Android screen-capture or accessibility permissions.

### DOM fallback

1. Temporarily hide chrome with the `skipSelectors` list in `reader_bridge.js` (`#content-overlays`, `nav`, `header`, `footer`, `button`, `[aria-hidden="true"]`, etc.).
2. Read `document.body.innerText`, which preserves rendered word spacing and block order.
3. Restore the hidden elements.
4. Normalize whitespace, drop empty lines, known UI labels (`next`, `previous`, `page`, `library`, …), standalone page numbers, and consecutive duplicate lines.
5. If the result has almost no spaces (Kindle historically rendered words as positioned `span.k4w` elements), rebuild the text from those spans.

## Page turns

The turn chain, in order:

1. Click the first visible element matching `turnSelectors`:
   `#kr-next`, `.kr-nav-right`, `[data-testid="next-page"]`, `[aria-label="Next page"]`, `[aria-label="Next Page"]`, `button[title*="Next" i]`, `[id*="next-page"]`, `.next-page`.
2. Dispatch `ArrowRight`, `PageDown`, and Space keydown/keyup events.
3. Click near the right margin at mid-height.
4. After each attempt wait 900 ms and extract again.

`changed` compares the new page with the last extracted text. The script updates `lastExtractedText` on every turn, including failed ones, so a duplicate page cannot be reported as new. Native playback also strips overlapping text between pages and treats a page that adds no new sentences as the end of the book.

## Page turns on the image reader

The OCR source advances by scrolling the next page image to the top of the reader: `scrollNext` computes the current page (the image spanning the viewport top), finds the next page image, and scrolls the window by that image's offset. The visible page therefore leads the audio by the read-ahead buffer, which is expected.

Kindle loads the next chapter's page images only after a real page turn, so when the scroll cannot reach a new page image the same `scrollNext` call runs the page-turn chain below and reports the strategy as `turned` in the `scrolled` reply. Native acquisition then polls for the next chapter's page images. A scroll and a turn that both leave the same current image mean the end of the book. Kindle's hidden sign-in iframes no longer mark the session expired.

## Session expiry

Only the **top-level** frame matching `signin` or `/ap/` marks the session expired. Amazon embeds hidden sign-in iframes inside the library and reader pages; treating every matching frame as expired caused false "Amazon sign-in required" states. When the top frame is a live reader, the expired flag is cleared.

## Library layout workaround

The WebView on some Android builds reports a zero-height CSS viewport (`100vh` is 0), which collapses Kindle's library grid to zero height even though the book data and covers are loaded. The injected script detects the library page and sets explicit heights on `#library` and its ancestors when the grid height is below 100 px. The fix runs from the 2.5 s readiness heartbeat, so it survives the SPA navigation from the landing page to the library.

## Repairing DOM drift

1. Reproduce on a debug build, open **Dev**, and press **Probe**.
2. Open a book and watch the frame list. The correct content frame has the largest text length and a book URL. Press **Use** if automatic selection picked the wrong frame.
3. Press **Extract** and inspect the text. Missing paragraphs usually mean a chrome selector is too broad; leaked toolbar text means a selector is missing.
4. Press **Turn** and check the reported strategy. A turn that only works via `margin-click` means the button selectors need updating.
5. Press **Copy report** and keep it with the bug report.
6. Edit `skipSelectors`, `turnSelectors`, or `excludedUrls` in `reader_bridge.js`, rebuild, and rerun the fixture tests and the real-book check.

## Tests

- `ReaderSessionInstrumentedTest` loads `assets/fixture/reader.html` through `WebViewAssetLoader`, verifies extraction, two consecutive page turns, and the chapter boundary; it also loads `assets/fixture/chapter.html` and verifies that a scroll with no next page image falls back to the page control.
- `PageOcrInstrumentedTest` draws a known line on a bitmap and verifies ML Kit reads it on the device.
- `OcrPageSourceTest` covers current-page selection and the last-page label.
- `PlaybackControllerTest` covers the queue, read-ahead, overlap dedupe, end-of-book pause, and page errors.
- `SpeechEngineInstrumentedTest` speaks one sentence with the platform engine (skipped when the device has no engine).
