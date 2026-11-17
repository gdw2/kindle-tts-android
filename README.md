# kindle-tts-android

Unofficial Android read-aloud app for Kindle for Web. It opens `read.amazon.com` in a WebView, captures each rendered page bitmap, transcribes it with on-device OCR, and speaks it with the system text-to-speech engine.

No DRM removal, no book downloads, and no Amazon credentials are handled by the app. It is not affiliated with Amazon.

## Build

```bash
devbox install          # toolchain: JDK, Gradle, Android CLI
devbox run setup        # download Android SDK 35, build tools, emulator image
devbox run build        # app/build/outputs/apk/debug/app-debug.apk
```

### Releases

Every push to any branch builds a signed release APK and publishes it as a GitHub release tagged `v<major>.<minor>.<patch>`, where the patch number is the highest existing `v*` tag plus one. The APK's `versionName` is the same number, so update checkers can compare versions. Push a tag to bump further: `git tag v0.2.0 && git push origin v0.2.0`.

The signing key must stay identical across releases, because an APK signed by a different key cannot be installed over the previous one. It is kept in the repository secrets and, for reference, in the git-ignored `release.keystore` and `release-signing.properties`. Create one with:

```bash
keytool -genkeypair -keystore release.keystore -alias kindle -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=kindle-tts"
base64 -w0 release.keystore   # paste into the KEYSTORE_BASE64 secret
```

The secrets are `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`kindle`), and `KEY_PASSWORD`; a local signed build uses the same names as environment variables. Losing the key means each install must be uninstalled before the next update.

The emulator is optional:

```bash
devbox run emulator     # headless
devbox run emulator-window
```

## Use

Add `https://github.com/gdw2/kindle-tts-android` in [Obtainium](https://github.com/ImranR98/Obtainium) to install each release APK. To install by hand:

```bash
adb install -r kindle-tts-<version>.apk   # from the latest release
```

A locally built debug APK has a different signature, so uninstall it once before switching to a release build.

Sign in to Amazon inside the app, open a book, and press **Play**. The notification keeps pause and stop available while the screen is off.

## Limitations

- Kindle for Web renders pages as images, so the app depends on OCR. Names, punctuation, and stylized headings can be misread.
- The visible page runs ahead of the audio because OCR reads ahead.
- Kindle for Web's DOM is undocumented and can change; the in-app Dev screen has a diagnostics report for repairs.
- Personal use only. Respect Amazon's terms of service.

See [docs/reader-integration.md](docs/reader-integration.md) for the Kindle integration, [docs/manual-test.md](docs/manual-test.md) for the test checklist, and [docs/scrcpy.md](docs/scrcpy.md) for remote control.

## License

MIT. See [LICENSE](LICENSE).
