# Echo Combine Extension

An extension for [Echo](https://github.com/brahmkshatriya/echo) that combines a metadata provider with separate audio providers.
For example, Spotify supplies metadata and YouTube Music supplies audio.

Combine sends feeds and library actions to the metadata extension. Only configured audio extensions supply playback streams.
The metadata extension still loads track details, but Combine discards its streams. It never calls that extension to resolve streamable media.

## Configuration
Inside the Echo app:
1. Go to **Settings -> Extensions**.
2. Tap on the **Combine Extension** settings icon.
3. Select your preferred **Metadata Extension** from the dropdown list.
4. Select your **Preferred Stream Extension** from the dropdown list.
5. Add alternative stream extensions in priority order, if needed.
6. Enable **First Available Mode** to search providers in order and stop at the first playable match.

The metadata extension is not an audio option. Old settings that select it for audio are ignored.
If no configured audio extension finds a match, the track has no streams. Combine never falls back to the metadata extension.

## Matching and fallback

Combine compares complete titles and the primary artist. It ignores punctuation and featured-artist credits, but keeps acoustic, live, remix, and other version labels.
It also supports filenames such as `Evil Deeds - Eminem.m4a`. Unknown title differences can still prevent a match.

If a provider throws an error during stream resolution, Combine tries the remaining audio providers in priority order.
This includes provider-side 403/429 errors. HTTP errors raised later by Echo's player are outside this extension's API.
First Available mode avoids unused backup searches. Combine does not prefetch queue items or cache stream URLs.

## Stable and nightly builds

- `main` is the stable branch. Each push publishes a normal release named `v<commit>`.
- `nightly` is the development branch. Each push publishes a prerelease named `nightly-<commit>`.
- A daily build at 00:00 UTC checks out `nightly`, not `main`.
- The **releases** workflow also supports manual builds of either branch.

Nightly releases never become GitHub's latest stable release. Rebuilding the same commit updates its existing release.
The workflow no longer deletes old releases, so nightly builds cannot delete stable releases.
Tests must pass before a signed package is published. Repository secrets `KEYSTORE_B64` and `PASSWORD` supply the signing key.

Both channels use the same extension ID. Importing a nightly package replaces the stable installation, rather than installing a second copy.
Nightly packages show **Combine Extension Nightly** and disable automatic updates, because Echo's updater only selects the latest stable release.
Download and import new nightly packages manually from the repository's prereleases or workflow artifacts.

Develop and test on `nightly`. Merge approved changes into `main` to publish them as stable.
The workflow must exist on GitHub's default branch (`main`) before scheduled builds can run.

## Repository automation

New or reopened issues and pull requests with no assignee are automatically assigned to `dutixlf`.
Existing assignments are preserved. The workflow uses event metadata only and never executes pull request code.
The autoassign workflow must reach the default branch before issue assignment activates.

## Verification

Run the offline regression tests and build the extension JAR with JDK 21:

```sh
bash .github/scripts/test-release-metadata.sh
node .github/scripts/test-autoassign.mjs
bash ./gradlew :ext:check :ext:shadowJar
```

The tests use fake providers. They need no provider accounts or live music services.
