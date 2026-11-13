# Spec Delta

## Purpose

Turns the extracted text of the current Kindle page into continuous speech, keeps acquiring text ahead of playback, and keeps audio running while the screen is off.

## ADDED Requirements

### Requirement: Playback speaks the current page

The app SHALL let the user start spoken playback of the page currently open in the reader and SHALL indicate whether playback is idle, playing, or paused.

#### Scenario: Start reading
- **WHEN** the user starts playback on an open book page
- **THEN** the page text is spoken from its beginning and the UI shows a playing state

#### Scenario: Stop reading
- **WHEN** the user stops playback
- **THEN** speech stops promptly, the queue is cleared, and the UI shows an idle state

### Requirement: Playback is independent of the visible page

Playback SHALL continue by acquiring text ahead of the spoken position, so speech does not stop merely because the visible page has not been turned by the user. The visible page MAY run ahead of the spoken position.

#### Scenario: Screen off, no user input
- **WHEN** playback is started and the screen is then turned off with no further user input
- **THEN** speech continues beyond the first page until the end of the available text

#### Scenario: Prefetch does not interrupt audio
- **WHEN** the app acquires and extracts the next page while speech is playing
- **THEN** audio continues without a gap, restart, or repeated sentence

#### Scenario: Prefetch fails
- **WHEN** page acquisition fails while speech is playing
- **THEN** the already buffered text is spoken to the end and playback pauses with an error instead of stopping mid-sentence

### Requirement: No text is skipped or duplicated at page changes

The spoken sequence SHALL contain each page's readable text exactly once when transitioning from one page to the next.

#### Scenario: Transition between pages
- **WHEN** speech crosses a page boundary
- **THEN** the queue speaks the end of the first page and the start of the next page once each, in order

### Requirement: User controls playback

The user SHALL be able to play, pause, resume, stop, and adjust speech rate, and the chosen rate SHALL apply to the remainder of the session.

#### Scenario: Pause and resume
- **WHEN** the user pauses and later resumes
- **THEN** speech resumes from the paused sentence rather than restarting the page

#### Scenario: Change speech rate
- **WHEN** the user changes the speech rate during playback
- **THEN** subsequent speech uses the new rate within a few sentences and the setting persists for the session

### Requirement: Playback continues with the screen off

The app SHALL keep audio playing while the screen is off or the app is not in the foreground, and SHALL expose pause and stop from the system notification.

#### Scenario: Lock the device
- **WHEN** the device is locked during playback
- **THEN** speech continues and a notification shows the current book with pause and stop actions

#### Scenario: Notification controls
- **WHEN** the user taps pause or stop in the notification while the screen is off
- **THEN** the corresponding action takes effect without unlocking the device

### Requirement: Playback pauses at a chapter boundary

When no further text can be acquired without leaving the current chapter, the app SHALL stop acquiring text, pause playback, and tell the user that the chapter ended.

#### Scenario: Chapter end reached
- **WHEN** the queue drains and the current chapter has no next page
- **THEN** playback pauses, no text is repeated, and the user is told the chapter ended

### Requirement: Interruptions are handled

Playback SHALL pause when another app takes audio focus, such as an incoming call, and SHALL NOT resume automatically until the user asks.

#### Scenario: Incoming call
- **WHEN** audio focus is lost during playback
- **THEN** speech pauses and remains paused after focus returns

### Requirement: Speech engine problems are reported

If no speech engine or suitable voice is available, the app SHALL report the problem and remain usable for reading.

#### Scenario: No TTS engine
- **WHEN** playback is requested on a device without a usable speech engine
- **THEN** the app shows an explanatory error and does not crash or hang
