# Design

## Context

See `proposal.md` for motivation and `specs/` for the behavior contract. The repository is empty apart from OpenSpec; the app is greenfield. The only external integration is Kindle for Web, whose DOM and page-turn controls are undocumented and change without notice.

Two constraints drive the design:

- Kindle for Web renders book content inside nested iframes. We do not know up front whether the content frame is same-origin with the top page, and Amazon may change that.
- Kindle for Web's current reader renders every book page as a full-page bitmap served from `blob:` image URLs. The visible text is absent from the DOM, open shadow roots, JavaScript globals, and storage, so DOM scraping cannot read a book.
- Text must be acquired independently of what is on screen, including while the device is locked.

## Goals / Non-Goals

**Goals:**

- Prove the whole loop end to end: sign in, open a book, extract a page, speak it, acquire the next page without user input.
- Keep everything fragile about Kindle for Web in one injected script module and one diagnostics screen, so selector repairs are cheap.
- Make failures observable: no silent empty queues, no repeated text at page boundaries.
- Keep the app free of Amazon credentials and book content storage.

**Non-Goals:**

- Automatic chapter navigation. Reaching the end of a chapter pauses and asks the user.
- Display/audio synchronization. The visible page is allowed to lead the audio; the design does not attempt to turn it back.
- Per-book position persistence, bookmarks, word highlighting, footnote/table reflow, and non-linear reading.
- Any DRM handling, content download, or OCR. (Screenshot OCR is the fallback only if DOM extraction proves impossible; it is not built now.)

## Decisions

### Native Android with Kotlin + Compose and the platform WebView

Android WebView, `android.speech.tts.TextToSpeech`, and a foreground service are the exact primitives the requirements need. Flutter and React Native were considered to answer the "test on Linux" question: neither offers a usable Linux desktop WebView, so testing on Linux still means an Android emulator, and they add a platform layer over the same Android APIs. Toolchain: JDK 17, Android SDK (platform 35, build tools, emulator), Gradle wrapper, minSdk 26, compile/target 35. `/dev/kvm` is available for the emulator.

### One WebView, one Activity, one reader session

`MainActivity` hosts a full-screen WebView plus a compact Compose playback bar. A process-scoped `ReaderSession` owns a weak reference to the WebView and marshals all calls onto the main thread. The playback foreground service holds the queue and TTS engine and asks `ReaderSession` for pages through a `PageSource` interface.

Alternatives considered: putting playback entirely in the Activity (dies when the screen is off or the Activity is recreated), and a separate process for the WebView (WebView cannot run outside an Activity's window in practice).

### Capture page images and OCR them on device

The injected script locates the reader's page images (`.kg-full-page-img img`), draws the current page image to a canvas, and returns it to native as a JPEG base64 payload. Native decodes it and runs ML Kit's bundled on-device text recognizer. `OcrPageSource` chooses the page whose image top is flush with the viewport, OCRs the full page image (which contains the whole page, not just the visible slice), and feeds the text to the existing playback queue. To advance, `scrollNext` computes the next page image and scrolls the window by that image's offset; the visible page therefore leads the audio by the read-ahead buffer. An incomplete trailing sentence is held and merged with the next page; at the end of the book it is flushed before playback pauses.

This is the primary path because the reader is image-based. DOM text extraction remains available for text-rendered pages and the local fixtures. Capturing the image inside the WebView (rather than an OS screenshot) keeps the pipeline working with the screen off and avoids screen-capture permissions.

Alternatives considered: OS-level screenshots (fails when the display is off and needs permissions), OCR in the cloud (network, privacy, latency), and reconstructing glyph IDs from `page_data_*.json`/`glyphs.json` (much more work and more brittle). Word bounding boxes for highlighting are out of scope this iteration.

### Extract DOM text through a bridge injected into every frame

The injected script is installed with `WebViewCompat.addDocumentStartJavaScript` with an allowed-origin rule of `*`, so it runs in every frame regardless of origin. Each frame's script:

1. Checks whether the frame contains readable content (a plausible body text length after excluding overlays).
2. Registers with a narrow `@JavascriptInterface` bridge and polls it for commands.
3. Executes `EXTRACT_PAGE` and returns the ordered text, or `TURN_PAGE` and synthesizes a user gesture.

This avoids relying on same-origin frame traversal and keeps working if Amazon moves content into a cross-origin frame. `evaluateJavascript` on the top frame alone was rejected because it cannot reach cross-origin children. `addJavascriptInterface` is exposed only to Amazon and local fixture pages; navigation is restricted to Amazon domains, with other links opened in a browser.

**Page text extraction (fallback):** take the content frame with the largest readable text, clone its body, drop known chrome and overlay containers (`#content-overlays` and whatever the diagnostics screen identifies), walk block elements in DOM order, and join with blank lines. Prefer `innerText`-style block semantics over concatenating word spans; Kindle historically wraps words in `span.k4w` (per the ACRExtensions/bookmarklet work), so a word-span fallback joins spans with spaces and relies on paragraph boundaries.

**Page turn:** try, in order, the reader's next-page hit target (discovered via diagnostics and kept in a selector list), a synthetic `keydown`/`keyup` `ArrowRight` on the content document, and a synthetic click on the right-side page margin. After each attempt wait for a content-change signal or a 1.5 s timeout. The page text is compared with the previous page to detect success; no change means either a slow render (retry once) or a chapter/document boundary.

### Read-ahead is a page buffer, not display sync

Playback keeps a buffer of acquired pages (default 2 pages / at least 4 utterances ahead). When the buffer drops below the threshold, `ReaderSession` acquires the next page, which turns the visible page forward. The UI shows a subtle "reading ahead" state; it does not try to keep the visible page aligned with the audio.

Rationale: one WebView can only display one page, so any text ahead of the spoken page necessarily moves the display ahead. This matches the behavior of commercial read-aloud apps. Turning back after prefetch was rejected as visually jarring and racy.

### Sentence-level queue, shallow enqueue

`PageText` is normalized (whitespace, hyphenated line breaks, drop empty lines) and split into sentences of roughly 120–350 characters at punctuation, falling back to clause/word boundaries for long sentences. Each `Utterance` carries an id, page index, and text. Into `TextToSpeech` we enqueue at most two utterances at a time; the rest stay in our queue. This makes pause/resume and rate changes take effect promptly: pause calls `stop()` and resumes by re-speaking the current utterance; a rate change calls `setSpeechRate()` and re-enqueues the remaining utterances.

`UtteranceProgressListener.onDone` advances the queue; `onError` pauses playback with a visible error. `onStart`/`onDone` drive the playing state and the notification.

### Foreground service for screen-off playback

A `mediaPlayback` foreground service owns TTS, the queue, audio focus, and a partial wake lock held only while playing. The notification shows the book title and page position with pause/stop actions (and resume when paused). Audio focus loss pauses and does not auto-resume.

The WebView stays alive in the stopped Activity and continues to serve `EXTRACT_PAGE`/`TURN_PAGE`. If the Activity is destroyed under memory pressure, already-buffered text keeps playing; the service pauses when the buffer drains and the notification tells the user to reopen the app. The exact page position is not persisted.

### Diagnostics are part of the app

A debug screen (debug builds only, reachable from the playback bar) shows: frame inventory (URL, origin, readable text length), the last extracted page text, selector and page-turn strategy used, and buttons to re-run extraction, turn one page, and copy the report. This is the primary tool for repairing Kindle DOM drift and for verifying the integration without Amazon credentials in automated tests.

### Testing strategy

- Unit tests: sentence splitter, text normalization, queue ordering, dedupe across page boundaries, boundary detection from identical page text.
- Instrumented test with a local fixture: `assets/fixture/reader.html` mimics the reader (nested same-origin iframe, word spans, overlay container, a fake next-page control that swaps content). It exercises `WebViewReaderSession` extraction and page turn without network or Amazon.
- Manual emulator checklist: sign in, open a purchased book, start playback, lock the screen, wait past a page, verify notification controls, reach a chapter end. Real Kindle content cannot be asserted in CI.

## Risks / Trade-offs

- **Kindle DOM or page-turn controls change** → selectors and strategies live in one injected module with a diagnostics report; the page-turn chain has three fallbacks; a failed advance is surfaced as an error rather than silent repetition.
- **OCR misreads text** → OCR runs on the full-page bitmap at native resolution; common Kindle fonts are clean, but names, punctuation, and drop caps can be wrong. Failures are visible in the diagnostics report and the text is still readable; word highlighting is deferred.
- **The page-image bridge payload is large** → page images are JPEG-compressed before crossing the bridge, and capture is retried with a generous timeout. A failure stops playback with an error instead of playing garbage.
- **Kindle preloads only a few page images** → the app advances by scrolling the next page image into view and detects the change by image source; a scroll that does not change the page is treated as the end of the book.
- **WebView throttles rendering or JS while the app is backgrounded** → keep a partial wake lock, call `resumeTimers` when the Activity stops, and prefetch up to the buffer limit before the screen goes off. If acquisition still stalls, buffered text finishes and playback pauses with an error. Verify on a real device early; this is the riskiest requirement.
- **Cross-origin content frames** → all-frame injection plus a polling bridge avoids frame traversal, at the cost of a bridge that runs in every frame. Only three commands are exposed.
- **Amazon account/session handling** → credentials never leave the web page; navigation is restricted to Amazon domains; the app stores no book text beyond the in-memory playback buffer.
- **TTS voice availability and quality** → degrade with a clear error when no engine or language is available; speech rate is clamped to the engine's supported range. No attempt to tune OEM voices in this iteration.
- **Legal/ToS** → the app only reads text the signed-in user can already see in their browser session and does not circumvent DRM. This is a personal-use tool; the project should state that in its README rather than build around it.
- **Activity destruction during screen-off playback** → audio survives only as far as the buffer; the user is told to reopen the app. Accepted for the first iteration.

## Migration Plan

Greenfield: no existing users or data. Rollout is a signed debug APK installed on a device/emulator; rollback is uninstalling the app. No backend or schema migration.

## Open Questions

- Whether Kindle for Web accepts synthetic key events or needs a coordinate click: resolved during the first integration task via the diagnostics screen; the design's fallback chain covers either outcome.
- Whether a chapter end is distinguishable from a slow render: detected by text equality plus one retry; if unreliable on real books, the diagnostics report will show it and the detection threshold can be adjusted without changing the specs.
