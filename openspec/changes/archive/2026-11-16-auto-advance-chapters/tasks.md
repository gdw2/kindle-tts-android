# Tasks

## 1. Bridge fallback

- [x] 1.1 Extract the page-image measurement used by `handleScrollNext` into helpers.
- [x] 1.2 In `handleScrollNext`, turn to the next page when the scroll leaves the same current page image, using the existing selector/arrow/margin chain, and report the strategy.
- [x] 1.3 Add `assets/fixture/chapter.html`: one page image and a `#kr-next` control that swaps in the next chapter's image, with no scroll room.
- [x] 1.4 Instrumented test: `scrollNextPage()` on that fixture changes the current page image.

## 2. Acquisition and playback

- [x] 2.1 Widen the `OcrPageSource.nextPage()` polling window so a chapter load is not mistaken for a boundary.
- [x] 2.2 Report the end of the book rather than a chapter boundary in `PlaybackController`.
- [x] 2.3 Update `PlaybackControllerTest` boundary cases for the end-of-book wording.

## 3. Documentation and verification

- [x] 3.1 Update `docs/reader-integration.md` page-turn and test sections.
- [x] 3.2 Add a manual checklist row in `docs/manual-test.md` for crossing a chapter boundary hands-free.
- [ ] 3.3 Verify on a real book: playback crosses a chapter boundary with the screen off, does not repeat text, and stops with "End of book." at the end of the book.
- [x] 3.4 Run `devbox run test` and `devbox run connected-test`.
