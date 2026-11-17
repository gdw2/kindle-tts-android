# Design

## Where the fallback turn lives

The reader knows nothing about Kindle's DOM; the bridge does. So the decision "scrolling did not reach a new page" belongs in `handleScrollNext` in `reader_bridge.js`:

1. Scroll one page height, as today.
2. Re-measure the page images. If the viewport did not move, or the image under the viewport top is the one that was there before, click the first visible `turnSelectors` match, else send ArrowRight/PageDown/Space, else click the right margin — the same chain `turn` already uses for text-rendered pages.
3. Report the turn strategy in the `scrolled` reply so the diagnostics screen and logs can show what happened.

Calling into the DOM from native code is not an option: `turnPage` requires a content frame with enough text, which the image reader does not have.

## Native side

`OcrPageSource.nextPage()` keeps its contract and existing change detection (`current.src != before`). It polls up to ~5 s instead of ~1.5 s, because loading the next chapter is slower than scrolling within one. Nothing else in the page-source interface changes, so `CompositePageSource` and `PlaybackController` stay unaware of chapters.

## Boundary semantics

After the change, a `Boundary` from the image reader means "a scroll and a turn both left the same page image", i.e. the end of the book. The playback message follows that: "End of book." instead of "End of chapter. Turn the page, then press Play."

## Risks

- Kindle's next-page control is not documented. If the fallback fires and the selector chain finds nothing usable, the reader reports a boundary, which is the current behavior — no regression, just no progress.
- A chapter that takes longer than the polling window to render would look like the end of the book. Pressing Play re-reads the visible page, so recovery is one tap; the window is set generously for that reason.
- The fallback click never runs mid-chapter: within a chapter the scroll always changes the page image first.

## Alternatives rejected

- Auto-pressing the next control unconditionally instead of scrolling: slower, one page per turn, and it abandons the scrolling path that already works across the whole chapter.
- Adding a `nextChapter()` method to `PageSource`: extra interface surface with one implementation, and the chapter/end-of-book distinction lives inside the page source anyway.
