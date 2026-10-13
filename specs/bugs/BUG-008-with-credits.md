---
type: bug
context: GitHub issue 8; Dreamville, Revenge Of The Dreamers III
---

# BUG-008: Parenthesized `with` credits prevent track matching

## Problem and root cause

Spotify includes `(with Artist …)` credits in track titles; Deezer omits them.
The shared title cleaner handles feat/ft/featuring but not with credits, so both
search queries and exact title comparisons retain unwanted artist text.
A comparison of the public album catalogs rejected 16 of 18 corresponding titles.
This is a missing credit format, not proof of a playback or provider failure.

Security impact: NONE; no security exploit path identified. Risk: low.

Catalog sources:
- https://open.spotify.com/embed/album/2n3quCZ0anEa46j2IveacI
- https://api.deezer.com/album/102427652

## Approved TDD fix plan

1. RED: Exercise all 18 title pairs through track loading and stream resolution,
   in both provider modes and both matching directions. Verify cleaned searches,
   artist validation, and preservation of Acoustic/Live/Remix/Interlude labels.
2. GREEN: Add `with` only to the bracketed-credit markers in the shared cleaner.
   Do not strip arbitrary brackets or unbracketed `with` in song titles.
3. Verify: `bash ./gradlew :ext:check :ext:shadowJar --console=plain`.
4. Commit locally on nightly; exclude the user's README edits. Do not push.

## Acceptance criteria

- All catalog title pairs resolve to the matching audio track.
- Version labels and artist validation remain effective.
- Metadata is never used for playback.
- Existing regression tests pass.

## Resolution

Pending the RED/GREEN checks. Live playback with actual providers still requires
confirmation from the issue reporter.
