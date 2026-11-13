# Tasks

## 1. Toolchain and app shell

- [x] 1.1 Provision JDK 17 and the Android SDK (platform 35, build tools, emulator, system image) on this Linux host; verify `sdkmanager --list` succeeds and `emulator -accel-check` reports KVM usable.
- [x] 1.2 Scaffold the Gradle project at the repository root: root build files, `app` module, wrapper, Compose, minSdk 26, compile/target 35; verify `./gradlew assembleDebug` produces an APK.
- [x] 1.3 Boot an emulator (API 35) and install the APK; verify the app launches to a placeholder screen without crashing.
- [x] 1.4 Add the manifest declarations (INTERNET, media playback foreground service, notifications) and a top-level Compose screen with a reader area and playback bar; verify the screen renders and `adb logcat` shows no permission or manifest errors.

## 2. Kindle web reader

- [x] 2.1 Implement the WebView screen that loads `read.amazon.com` with JavaScript and DOM storage enabled, restricts top-level navigation to Amazon hosts, and opens other links in a browser; verify a manual sign-in succeeds and the library is visible.
- [x] 2.2 Verify Amazon session persistence by force-stopping the app and relaunching; the library must appear without a new sign-in prompt.
- [x] 2.3 Build the debug diagnostics screen showing frame inventory (URL, readable text length), last extracted text, and buttons to re-extract, turn one page, and copy the report; verify it reports frames on both the library and an open book.
- [x] 2.4 Implement all-frame script injection and a narrow JavaScript bridge with `EXTRACT_PAGE`/`TURN_PAGE` commands and timeouts; verify the diagnostics screen sees the content frame register and can round-trip a command.
- [x] 2.5 Implement page text extraction (drop overlays and chrome, walk blocks in order, word-span fallback) plus a local fixture asset and an instrumented test; verify the fixture extraction matches the expected text and a real book page yields readable paragraphs.
- [x] 2.6 Implement the page-turn chain (next-page target, synthetic ArrowRight, right-margin click) with content-change detection and one retry; verify the fixture test advances content and that identical page text is reported as a boundary rather than repeated text.
- [x] 2.7 Detect an expired Amazon session during extraction or page turn, surface the sign-in page, and pause playback; verify by clearing WebView cookies mid-session and observing the sign-in state and error.
- [x] 2.8 Verify manual reader use while playback is idle: browse the library, open a book, and turn a page by touch, then confirm the next extraction reflects the new page.

## 3. TTS core

- [x] 3.1 Implement text normalization and sentence splitting in pure Kotlin; unit tests cover hyphenated line breaks, long sentences, and whitespace; verify `./gradlew test` passes those tests.
- [x] 3.2 Add a `SpeechEngine` wrapper over Android `TextToSpeech` (init, rate, speak, stop, progress callbacks) with an injectable interface; unit tests use a fake engine for start/finish/error paths, and an emulator smoke test confirms real speech starts and stops.
- [x] 3.3 Implement the playback queue state machine (idle/playing/paused, current utterance, shallow enqueue, resume from paused sentence, rate change); unit tests verify ordering, pause/resume position, and rate re-enqueue behavior.
- [x] 3.4 Wire the playback bar to the state machine with play, pause, stop, and rate controls; verify each control on the emulator against an open book page.

## 4. Read-ahead and boundaries

- [x] 4.1 Implement the read-ahead coordinator that requests pages from a `PageSource` when the page/utterance buffer falls below threshold; unit tests with a fake source verify requests happen before the queue drains and stop at the buffer limit.
- [x] 4.2 Enforce exactly-once text across page transitions in the queue; unit test with a fake source verifies the last utterance of page N and first of page N+1 appear once each, in order.
- [x] 4.3 Handle the boundary result: stop acquisition, pause when the queue drains, and show the chapter-ended message; unit test with a fake source, then verify manually at a real chapter end.
- [x] 4.4 Handle extraction failure during playback: finish buffered text, then pause with an error; unit test with a failing fake source verifies no mid-sentence stop and no crash.

## 5. Screen-off playback

- [x] 5.1 Move TTS, the queue, audio focus, and a playing-only partial wake lock into a bound media-playback foreground service; verify the service starts with playback and the wake lock is not held while idle.
- [x] 5.2 Add the notification with book position and play/pause/stop actions; verify the actions work from the lock screen on the emulator.
- [x] 5.3 Pause playback on audio focus loss and do not auto-resume; verify by starting audio in another app during playback.
- [x] 5.4 Verify page acquisition with the screen off: play past at least three pages with no input; if acquisition stalls while backgrounded, apply the designed mitigations (`resumeTimers`, prefetch, wake lock) and retest.
- [x] 5.5 Handle Activity destruction: verify buffered text keeps playing, then playback pauses and the notification asks the user to reopen the app (test with "Don't keep activities" enabled).
- [x] 5.6 Report an unavailable TTS engine as an error without crashing; verify by disabling the emulator's TTS engine and requesting playback.

## 6. Integration and documentation

- [x] 6.1 Run the end-to-end manual checklist with a real book: sign in, open, play, lock the screen across pages, use notification controls, reach a chapter end; record the result in `docs/manual-test.md`.
- [x] 6.2 Write the README with purpose, the no-DRM/no-credential statement, Linux build/run instructions, and known limitations (no chapter auto-advance, display leads audio, no position persistence); verify every command runs as written.
- [x] 6.3 Write `docs/reader-integration.md` covering the frame bridge, selector list, page-turn strategy, and how to use the diagnostics screen to repair Kindle DOM drift; verify a selector change can be made by following it alone.
- [x] 6.4 Run `./gradlew test assembleDebug` and the instrumented tests on the emulator; verify all green and attach the diagnostics report from a real book.

## 7. OCR pivot for image-rendered Kindle pages

- [x] 7.1 Add bundled ML Kit text recognition and verify a generated text bitmap is recognized on the emulator (`PageOcrInstrumentedTest`).
- [x] 7.2 Capture the rendered page bitmap from the WebView through the JavaScript bridge and verify a real Kindle page image crosses the bridge and is OCR'd (page 4, 424 characters).
- [x] 7.3 Implement `OcrPageSource` for page selection, one-screen page turns, OCR text, and boundary detection; verify its unit tests pass.
- [x] 7.4 Fix the Kindle library's zero-height layout by applying the height fallback from the injected script; verify the book grid renders after sign-in.
- [x] 7.5 Fix the false "Amazon sign-in required" state caused by hidden sign-in iframes; verify playback starts on a real book.
- [x] 7.6 Verify the page turn scrolls one page image and waits for the next image; verify the next page is OCR'd and the last page reports a boundary.
- [x] 7.7 Verify OCR and page acquisition continue with the screen off on a real book.
- [x] 7.8 Update `README.md`, `docs/reader-integration.md`, and `docs/manual-test.md` for the OCR pipeline and record the real-book results.
- [x] 7.9 Apply safe-drawing insets so the reader is not under the Android status bar; verify the Kindle Library button and the three-dot reader menu respond to taps.
- [x] 7.10 Prefer the most recently active reader frame, prune stale frames, and retry the current reader frame; verify switching from one book to another starts OCR on the new book.
- [x] 7.11 Change the page turn to scroll exactly one page image height and wait for the next image to load; verify the missing Carpathian passage between “in the midst of the” and “some sort of imaginative whirlpool” is recovered.
- [x] 7.12 Compare the OCR transcript around the page boundary with the Project Gutenberg Dracula text; verify every phrase in the 1,033-character gap is present.
- [x] 7.13 Hold an incomplete trailing OCR sentence until the next page arrives and merge it; flush it at the end of the book. Verified page 1 held “When I asked him if” and page 2 merged it into one sentence, and the last page flushed “material of which the record is composed, there is”.

## Workflow follow-up

- Archive the change after the project's review requirements are satisfied.
