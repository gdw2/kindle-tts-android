# kindle-tts-android

Unofficial Android read-aloud app for Kindle for Web. It opens `read.amazon.com` in a WebView, captures each rendered page bitmap, transcribes it with on-device OCR, and speaks it with the system text-to-speech engine.

No DRM removal, no book downloads, and no Amazon credentials are handled by the app. It is not affiliated with Amazon.

## Build

```bash
devbox install          # toolchain: JDK, Gradle, Android CLI
devbox run setup        # download Android SDK 35, build tools, emulator image
devbox run build        # app/build/outputs/apk/debug/app-debug.apk
```

The emulator is optional:

```bash
devbox run emulator     # headless
devbox run emulator-window
```

## Use

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sign in to Amazon inside the app, open a book, and press **Play**. The notification keeps pause and stop available while the screen is off.

## Limitations

- Kindle for Web renders pages as images, so the app depends on OCR. Names, punctuation, and stylized headings can be misread.
- The visible page runs ahead of the audio because OCR reads ahead.
- Kindle for Web's DOM is undocumented and can change; the in-app Dev screen has a diagnostics report for repairs.
- Personal use only. Respect Amazon's terms of service.

See [docs/reader-integration.md](docs/reader-integration.md) for the Kindle integration, [docs/manual-test.md](docs/manual-test.md) for the test checklist, and [docs/scrcpy.md](docs/scrcpy.md) for remote control.

## License

MIT. See [LICENSE](LICENSE).
