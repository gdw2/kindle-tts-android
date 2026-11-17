# Spec Delta

## REMOVED Requirements

### Requirement: Playback pauses at a chapter boundary

## ADDED Requirements

### Requirement: Playback spans chapters

The app SHALL keep acquiring text across chapter boundaries without user interaction and SHALL pause only when no further text can be acquired.

#### Scenario: Chapter end reached
- **WHEN** the queue drains and the current chapter has no next page
- **THEN** the app turns to the next chapter, keeps speaking without pausing, and repeats no text

#### Scenario: End of book reached
- **WHEN** no page turn produces new readable text
- **THEN** playback pauses, no text is repeated, and the user is told the book ended
