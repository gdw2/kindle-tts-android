# Spec Delta

## MODIFIED Requirements

### Requirement: Pages can be acquired programmatically

The app SHALL advance the image-rendered reader by one page without user input, including across chapter boundaries, and report whether the page image or page label changed.

#### Scenario: Successful page advance
- **WHEN** the app advances one page from a non-final page
- **THEN** the newly captured page image or page label differs from the previous page and its text is returned to the caller

#### Scenario: Chapter boundary crossed
- **WHEN** scrolling cannot reach a new page image because the current chapter has no next page
- **THEN** the app invokes the reader's next-page control, waits for the next chapter's page images, and returns the first page of the next chapter as text

#### Scenario: Content boundary reached
- **WHEN** both a scroll and the fallback page turn leave the same page image, for example at the end of the book
- **THEN** the app reports a boundary instead of returning duplicated text
