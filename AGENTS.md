# AGENTS.md

## Project

Unofficial Android read-aloud app for Kindle for Web. Repository: `gdw2/kindle-tts-android`. Default branch: `main`.

## Source of truth

- Behavior and acceptance criteria: `openspec/specs/`.
- In-flight changes and their tasks: `openspec/changes/`.
- Read the relevant spec and active change before changing behavior. Do not duplicate the contract here.
- Platform and Kindle integration notes: `docs/reader-integration.md`.
- Manual verification: `docs/manual-test.md`.

## Hard constraints

- No DRM removal, no book downloads, and no book-content storage beyond the in-memory playback buffer.
- Never read, store, log, or transmit Amazon credentials. Sign-in stays inside the WebView.
- Do not add references to any specific commercial read-aloud app in code, docs, commits, or issues. Use "commercial read-aloud apps" when context is needed.
- This is an unofficial project; do not imply Amazon affiliation.
- Do not commit personal system details, network addresses, credentials, or reading-library identifiers.
- Do not commit `.android-sdk/`, `.devbox/`, `.sdk/`, `build/`, `.gradle/`, `.kotlin/`, `local.properties`, or secrets.

## Workflow

- Use OpenSpec for behavior changes: `/opsx-propose`, `/opsx-apply`, `/opsx-update`, `/opsx-sync`, `/opsx-archive`, `/opsx-explore`.
- A bug fix that restores behavior already covered by a spec: fix, add a regression test, and commit directly. If the project requires tracking for it, create a change with `skip_specs: true`.
- A new or changed requirement: use a normal OpenSpec change with a delta spec.
- A pure internal change (manifest, dependency, docs, refactor): commit directly.
- Use devbox for the local toolchain; see `README.md` for commands. `devbox run connected-test` clears app data, so sign in again afterward.
- Keep the GitHub Actions APK build green.
