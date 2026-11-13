# Manual test checklist

Environment: Android emulator, API 35 (Google APIs), 1080x2400, Google TTS enabled. Fixture pages are local assets, so no Amazon account is needed for those rows. Real-book rows were run against Kindle for Web with the user's account.

| # | Check | Result |
|---|-------|--------|
| 1 | `devbox run build` produces `app/build/outputs/apk/debug/app-debug.apk` | Pass |
| 2 | `devbox run test` passes the JVM unit tests | Pass |
| 3 | `devbox run connected-test` passes the instrumented fixture, OCR, speech, and service tests | Pass (5/5; final suite green) |
| 4 | App launches to Kindle for Web's sign-in/landing page with the playback bar visible | Pass |
| 5 | Fixture: extraction returns clean paragraphs, no button or library text | Pass (instrumented + Dev screen) |
| 6 | Fixture: page turn advances content; the last page reports a boundary | Pass (instrumented) |
| 7 | Fixture: Play starts speech, Pause stops it, Play resumes, Stop clears the state | Pass |
| 8 | Fixture: speech-rate control changes the rate and persists in the bar | Pass |
| 9 | Long fixture: lock the screen; playback continues and advances from page 2 to page 4 with the wake lock held | Pass |
| 10 | Long fixture: at the end of the last page, playback pauses with "End of chapter" and releases the wake lock | Pass |
| 11 | Notification appears while playing with Pause and Stop actions; tapping them from the lock screen works | Notification and actions present; Pause/Resume/Stop intents pass in `PlaybackServiceInstrumentedTest`; lock-screen tap not visually verified |
| 12 | Disable the TTS engine, start playback: an explanatory error is shown and the app does not crash | Pass (`Text-to-speech engine failed to initialize`) |
| 13 | Start another audio app during playback: speech pauses and stays paused | Pass via `PlaybackServiceInstrumentedTest` (focus loss pauses; no auto-resume) |
| 14 | Enable "Don't keep activities", play, press Home: buffered text continues, then pauses and asks to reopen the app | Pass in an earlier build (Activity closed; buffered speech finished; paused with "Reader closed"). The current service test covers actions and Stop; teardown timing is best-effort. |
| 15 | Real Amazon account: sign in inside the WebView; the library appears | Pass — signed in on the emulator; the library grid renders with the layout fix |
| 16 | Real library: open a purchased book; the reader frame is selected automatically | Pass — a purchased book opened to page 6 |
| 17 | Real book: Play captures the page image, OCRs it, and keeps reading after the page is turned ahead | Pass — page 6 OCR 976 chars, page 7 OCR 1,069 chars; `scrollNext` advanced the page |
| 18 | Real book: touch page turns still work while playback is idle | Pass — a touch scroll changed the page and the next Play OCR'd the new page |
| 19 | Real book: reach a chapter/book end; playback pauses, does not repeat text | Pass — reached page 333 of 333, flushed the held tail, and paused with "End of chapter." |
| 20 | Clear WebView cookies mid-session: the app reports that Amazon sign-in is required and pauses | Pass — cookies cleared, top frame `/ap/signin`, Play reported "Amazon sign-in required" |
| 21 | Diagnostics report from a real book is attached to the change | Pass — `docs/diagnostics-real-book.txt` |
| 22 | OCR page 4 and page 5 after the library layout fix | Pass — 424 chars (page 4) and 985 chars (page 5) with readable paragraphs |
| 23 | Screen off during playback: OCR and page acquisition continue | Pass — `scrollNext` at 14:53:31, new page image, OCR 1,047 chars at 14:53:43, notification advanced to page 3 |
| 24 | Audio is audible through scrcpy | Pass — user confirmed speech; line and page-boundary pauses fixed in the current build |
| 25 | Kindle library grid renders after the WebView height workaround | Pass |
| 26 | Kindle reader top controls respond to taps | Pass after applying safe-drawing insets — the Library button navigates and the three-dot menu opens |
| 27 | Switch from one book to another and press Play | Pass — Dracula OCR'd its title page (725 chars) and the next page (999 chars) after the stale-frame fix |
| 28 | OCR transcript compared with Gutenberg Dracula around a page turn | Pass — page 1 ended "...the centre of" and page 2 began "some sort..."; the 1,033-character Gutenberg gap is present. OCR misread "Ordnance" as "Ordance"; the paragraph is not skipped. |
| 29 | Page boundary does not pause mid-sentence | Pass — page 1 held "When I asked him if" and page 2 merged it into "When I asked him if he knew Count Dracula…" |
| 30 | End of book boundary | Pass — reached the final page, flushed the held tail, and paused with "End of chapter." |
| 31 | Real-book diagnostics report | Pass — captured in `docs/diagnostics-real-book.txt` |

The final connected-test run uninstalls the app and clears its data; reinstall `app/build/outputs/apk/debug/app-debug.apk` and sign in afterward. The one open verification is tapping the notification actions from the actual lock screen (the action intents themselves pass the service test).
