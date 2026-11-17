# Proposal

## Why

Playback stops at the end of every chapter with "End of chapter. Turn the page, then press Play." That is the one remaining place where listening needs a human: the app only advances by scrolling to the next page image, and Kindle loads the next chapter's page images only after a real page-turn action. A listener with the screen off cannot provide that action.

## What Changes

- When scrolling cannot reach a new page image, the injected bridge clicks the reader's next-page control (falling back to the arrow keys and a right-margin click) and reports that it did.
- Page acquisition polls long enough for the next chapter's page images to load, so the next chapter's text is returned like any other page.
- A boundary now means the end of the book: no page turn produced new text. Playback pauses there and says so.
- Playback continues across chapter boundaries without pausing, in one uninterrupted sentence stream.

## Capabilities

### Modified Capabilities

- `kindle-web-reader`: programmatic page acquisition now crosses chapter boundaries by invoking the reader's page-turn controls.
- `tts-playback`: playback no longer pauses at a chapter boundary; it pauses at the end of the book.

## Impact

- `app/src/main/assets/reader_bridge.js`: the `scrollNext` handler performs the fallback turn when the page image does not change.
- `app/src/main/java/dev/kindletts/reader/ReaderSession.kt` and `app/src/main/java/dev/kindletts/ocr/OcrPageSource.kt`: longer polling window for a slow chapter load; boundary wording.
- `app/src/main/java/dev/kindletts/tts/PlaybackController.kt`: end-of-book message and documentation.
- New fixture `app/src/main/assets/fixture/chapter.html` plus an instrumented test covering the fallback turn.
- Fragile external dependency unchanged in kind: the fallback relies on Kindle's next-page selectors, already listed in `turnSelectors`; `docs/reader-integration.md` page-turn notes and the manual checklist must be updated.
- Out of scope: chapter navigation through a table of contents, and automatic movement between books.
