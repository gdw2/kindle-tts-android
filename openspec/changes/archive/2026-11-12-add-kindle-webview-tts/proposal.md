# Proposal

## Why

Kindle books can only be read aloud through Amazon's own apps, and existing open-source TTS tools require DRM removal or file downloads. Existing commercial read-aloud apps offer a good listening experience but are closed-source and cloud-dependent. This project provides an open alternative that never touches DRM: it reads the text already rendered by Kindle for Web (`read.amazon.com`) inside an Android WebView and speaks it with the platform TTS engine.

## What Changes

- Create a native Android app (Kotlin + Jetpack Compose) that hosts Kindle for Web in a WebView.
- Let the user sign in to Amazon inside the app's WebView; credentials are never handled by the app, and the Amazon session stays in WebView storage.
- Capture the rendered page image from Kindle's image-rendered reader (each page is a full-page bitmap served from a `blob:` URL) and transcribe it with on-device OCR (ML Kit).
- Keep the DOM extractor as a fast path for text-rendered pages and the local fixtures.
- Drive page turns programmatically so text can be acquired ahead of playback; playback must not depend on the visible page. The visible page may run ahead of the audio (the same behavior commercial read-aloud apps exhibit).
- Speak extracted text with Android's built-in `TextToSpeech`, with play/pause/stop, speech-rate control, and a continuous playback queue.
- Keep audio playing with the screen off using a foreground service and a partial wake lock.
- Pause playback at a chapter boundary and ask the user to continue, since automatic chapter navigation is not part of this iteration.

Out of scope for this iteration: DRM removal, downloading book files, automatic chapter navigation, cloud sync, custom cloud voices, iOS, word-level highlighting, OCR bounding-box overlays, OEM TTS tuning, and offline text caching.

## Capabilities

### New Capabilities

- `kindle-web-reader`: WebView session against Kindle for Web, in-WebView Amazon sign-in, page-image capture, on-device OCR transcription, and programmatic page acquisition.
- `tts-playback`: Continuous speech queue fed by read-ahead, playback controls, screen-off playback, and chapter-boundary handling.

### Modified Capabilities

None; this is a greenfield project with no existing specs.

## Impact

- New Android project at the repository root: Gradle build, app module, Compose UI, manifest with internet permission and a media-playback foreground service.
- New runtime dependencies: AndroidX Compose, lifecycle, and core libraries; ML Kit on-device text recognition; TTS uses the platform API.
- New assets: injected JavaScript for page-image capture and page navigation, kept in one place so Kindle changes are localized.
- Kindle for Web's current reader renders every page as a bitmap; OCR is required, so transcription accuracy depends on the page image and OCR errors are possible.
- Requires a device or emulator with Android System WebView and a TTS engine; Amazon account sign-in is required to reach a library.
- Fragile external dependency: Kindle for Web DOM structure and page-turn controls are undocumented and can change without notice.
