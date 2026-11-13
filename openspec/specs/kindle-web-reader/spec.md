# kindle-web-reader Specification

## Purpose

Lets the user reach their Kindle library through Kindle for Web inside the app, stay signed in to Amazon, and exposes the rendered page's text and page navigation to the rest of the app.

## Requirements

### Requirement: Kindle for Web opens in the app

The app SHALL open Kindle for Web (`read.amazon.com`) in an embedded browser surface as the first screen, without requiring a separate browser.

#### Scenario: First launch
- **WHEN** the user launches the app for the first time
- **THEN** Kindle for Web's sign-in or library page is displayed inside the app

#### Scenario: Session persists
- **WHEN** the user has signed in and restarts the app
- **THEN** the Kindle library is shown without asking for credentials again

### Requirement: Amazon sign-in is handled only by the web page

The app MUST NOT read, store, log, or transmit the user's Amazon credentials; the Amazon session SHALL remain in the embedded browser's own storage.

#### Scenario: Credentials are submitted
- **WHEN** the user enters an Amazon password on the web sign-in page
- **THEN** the app's own code never receives the password value and no credential is written to app storage

#### Scenario: User signs out
- **WHEN** the user signs out of Amazon in the embedded browser
- **THEN** subsequent launch shows the signed-out state and playback is unavailable until sign-in

### Requirement: Reader page content is transcribed

The app SHALL obtain the readable text of the current Kindle page even when Kindle renders the page as a bitmap image, by capturing the rendered page image from the WebView and running on-device OCR. It MUST NOT require Android screen-capture or accessibility permissions.

#### Scenario: Image-rendered book page
- **WHEN** a book page is rendered as a full-page image inside the WebView
- **THEN** the app captures that page image, OCRs it, and returns its paragraphs in reading order without toolbar or footer text

#### Scenario: Text-rendered page
- **WHEN** the page exposes readable DOM text
- **THEN** the app may use that text instead of OCR

#### Scenario: Sentence spans a page turn
- **WHEN** an OCR page ends mid-sentence
- **THEN** the trailing fragment is held and joined with the beginning of the next page so the sentence is spoken once, without a page-boundary pause

#### Scenario: OCR finds nothing
- **WHEN** the captured page image contains no readable text
- **THEN** transcription reports a failure and no text is queued

#### Scenario: Transcribe with the screen off
- **WHEN** the screen is off during playback
- **THEN** the page image is obtained from the WebView rather than from a device screen capture

### Requirement: Pages can be acquired programmatically

The app SHALL advance the image-rendered reader by one page without user input and report whether the page image or page label changed.

#### Scenario: Successful page advance
- **WHEN** the app advances one page from a non-final page
- **THEN** the newly captured page image or page label differs from the previous page and its text is returned to the caller

#### Scenario: Content boundary reached
- **WHEN** a page advance produces the same page image, for example at the end of the book
- **THEN** the app reports a boundary instead of returning duplicated text

### Requirement: Reader remains usable directly

The embedded reader SHALL keep its normal interaction (library browsing, opening a book, manual page turns) available whenever speech is not active.

#### Scenario: Manual navigation while idle
- **WHEN** playback is stopped and the user opens a book or turns a page by touch
- **THEN** the reader responds normally and the next extraction reflects the new page

### Requirement: Expired session is surfaced

The app SHALL detect when the Amazon session is no longer valid and SHALL surface the sign-in page instead of failing silently.

#### Scenario: Session expires during use
- **WHEN** an extraction or page advance lands on a sign-in page
- **THEN** playback pauses and the app indicates that Amazon sign-in is required
