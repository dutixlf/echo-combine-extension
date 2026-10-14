# Echo Combine Extension

[![Stable release][stable-release-badge]][stable-download]
[![Nightly channel][nightly-channel-badge]][nightly-download]
[![Stable build][stable-build-badge]][stable-runs]
[![Nightly build][nightly-build-badge]][nightly-runs]
[![Tests][tests-badge]][test-runs]
[![Android extension][android-badge]][echo]
[![JDK 21][jdk-badge]][build-docs]

**Keep your metadata provider. Choose separate providers for audio.**

Combine is a music extension for [Echo][echo]. It uses one provider for browsing and library actions, and other providers for playback.
For example, Spotify can supply metadata while YouTube Music supplies audio.

**The metadata provider never supplies playback streams.** Combine discards its stream descriptors and never calls it to resolve streamable media.

This README describes the current development code. Older published packages can differ from the behavior described here.

[Download stable][stable-download] · [Download nightly][nightly-download] · [Configure](#configuration) · [Build from source](#build-from-source) · [Report a bug][issues]

## How it works

```text
Echo → Combine → metadata provider
                ├─ search, home, library, albums, artists, playlists
                └─ track details and supported library actions

Track playback → configured audio providers → matching track → audio
```

| Role | Responsibility |
| --- | --- |
| Metadata provider | Feeds, search, track details, albums, artists, playlists, and supported library actions. |
| Preferred audio provider | First choice for track searches and playback streams. |
| Alternative audio providers | Additional stream choices and fallback sources, in priority order. |

Combine preserves the metadata track's identity and details. It replaces that track's streams with streams from configured audio providers.

Library actions depend on the metadata provider's capabilities. Combine cannot add an action that the underlying provider does not support.

## Download and install

| Channel | Source branch | Release tag | Intended use | Automatic updates |
| --- | --- | --- | --- | --- |
| [Stable][stable-download] | `main` | `v<commit>` | Published stable builds | Echo's stable release updater |
| [Nightly][nightly-download] | `nightly` | `nightly-<commit>` | Development and testing | Manual download and import |

Nightly packages are **GitHub prereleases**. They never become the latest stable release.

Both channels use the extension ID `echo_combine`. Importing one channel replaces the other. They do not install side by side.
Nightly packages display **Combine Extension Nightly** in Echo.

### Installation

1. Install [Echo][echo].
2. Install the providers that you want to combine.
3. Sign in to each provider that requires an account.
4. Download the `.eapk` asset from the selected release.
5. Import the file through Echo's extension manager.
6. Open the Combine settings and select your providers.

Workflow artifacts also contain the `.eapk` package. Extract the artifact ZIP before importing the package into Echo.

Combine does not bypass provider authentication, subscriptions, region restrictions, or missing audio availability.

### Switching channels

To switch to nightly, import a `nightly-<commit>` package. To return to stable, import a package from the [latest stable release][stable-download].

Nightly packages disable automatic updates. Echo resolves GitHub update URLs to the latest stable release, not to the newest prerelease.
Download and import subsequent nightly packages manually.

## Configuration

Open **Settings → Extensions**, then open the Combine settings.

| Setting | Purpose |
| --- | --- |
| **Metadata Extension** | Provider for browsing, track details, and library actions. |
| **Preferred Stream Extension** | Highest-priority audio provider. `None` disables the audio chain. |
| **2nd–5th Alternative Stream Extension** | Up to four backup audio providers. Each selected slot reveals the next slot. |
| **First Available Mode** | Stop searching after the first provider with a playable match. Off by default. |

Audio providers must be enabled and support both search and track loading. The selected metadata provider is not an audio option.

Provider priority follows the settings order. Duplicate selections are ignored. Missing or excluded providers do not block later configured backups.
An unset slot or `None` ends the priority chain.

Legacy multi-selection settings remain readable, but metadata selections are ignored. After upgrading, select your preferred provider and backup order explicitly.

### Playback modes

| Mode | Search behavior | Streams returned |
| --- | --- | --- |
| First Available **on** | Search providers sequentially, in priority order. | Streams from the first provider with a playable match. |
| First Available **off** | Search configured providers concurrently. | Matching streams from all providers, ordered by provider priority and then provider quality. |

**First Available means highest priority, not fastest response.** It avoids searches of unused backup providers after a successful match.

If no configured audio provider finds a playable match, the track has no streams. Combine never substitutes the metadata provider or an arbitrary installed provider.

## Track matching

Combine compares the complete normalized title and the metadata track's primary artist name. It does not accept title substrings or partial artist names.

Normalization ignores case, punctuation, accents, and featured-artist credits such as `feat.`, `ft.`, and `featuring`.
Acoustic, live, remix, and other version labels remain part of the title comparison.

| Metadata request | Audio candidate | Result |
| --- | --- | --- |
| `Evil Deeds` — Eminem | `Evil` — Eminem | Reject: different complete title. |
| `Desires (ft. Future)` — Drake | `Desires` — Drake | Accept: featured-artist credit differs. |
| `Only Love Can Save Me Now - Acoustic` | `Only Love Can Save Me Now` | Reject: the acoustic label is missing. |
| `Death by Rock and Roll – Acoustic,` | `Death by Rock and Roll (Acoustic)` | Accept: punctuation differs, version matches. |
| `Song` — John Smith | `Song` — John Doe | Reject: the artist name differs. |

Accepted examples assume matching artist metadata unless the table states an artist difference.

Combine also supports filenames such as `Evil Deeds - Eminem.m4a`. It recognizes `.m4a`, `.mp3`, `.flac`, `.ogg`, `.opus`, `.wav`, and `.aac` suffixes.
Filename artist text does not override conflicting artist metadata.

Version checks rely on provider titles. Missing or incorrect labels cannot establish which recording a provider actually returns.
Strict matching can reject valid recordings whose titles differ in ways that normalization does not cover.

## Fallback and limitations

If a provider throws during `loadStreamableMedia`, Combine tries the remaining configured audio providers in priority order.
Fallback uses the same track-matching rules and never includes the metadata provider.

Provider search, feed-page, and track-loading errors do not prevent other configured providers from working.
Cancellation remains cancellation. It does not start fallback work.

### What fallback covers

- Provider-side stream resolution failures, including 403/429 errors thrown during resolution.
- Alternative providers with a matching, playable version of the requested track.
- Both playback modes.

### What fallback does not cover

- HTTP errors raised later by Echo's player, after a provider returns its media URL.
- Queue prefetching or cached stream URLs.
- Matches hidden beyond the first page of the first non-sort search tab.
- Missing provider features or incorrect provider metadata.

The extension API does not give Combine a callback for later player HTTP failures. Automatic recovery from those failures needs support outside this extension.

### Related issues

| Report | Current development status |
| --- | --- |
| [#4: wrong song matching](https://github.com/dutixlf/echo-combine-extension/issues/4) | Regression tests reject shortened-title matches such as `Evil` for `Evil Deeds`. |
| [#8: title and artist mismatches](https://github.com/dutixlf/echo-combine-extension/issues/8) | Featured-artist normalization and full artist-name comparisons have regression coverage. |
| [#9: wrong track version](https://github.com/dutixlf/echo-combine-extension/issues/9) | Both reported acoustic requests have tests for matching and safe fallback in both playback modes. |
| [#7: 403/429 fallback](https://github.com/dutixlf/echo-combine-extension/issues/7) | Partial: provider resolution failures only, not later player HTTP errors. |
| [#5: slow skipping](https://github.com/dutixlf/echo-combine-extension/issues/5) | Unused backup searches are avoided in First Available mode. Queue prefetch remains unimplemented. |
| [#6: library crash](https://github.com/dutixlf/echo-combine-extension/issues/6) | No confirmed fix. Separate metadata-provider investigation is still needed. |

These entries describe code coverage, not proof of successful playback on every provider or closure of the GitHub reports.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| A track has no audio | Select an enabled audio provider distinct from the metadata provider. Search for the requested version directly in that provider. |
| A provider is missing from audio settings | Enable it in Echo. The metadata provider and Combine itself are intentionally excluded. |
| A featured track does not match | Check the full artist name and any remaining version labels. Not every title difference is a featured-artist credit. |
| Playback fails with 403/429 | Determine whether the provider throws during resolution or Echo's player fails afterward. Only the first case can trigger Combine fallback. |
| Track skipping is slow | Try First Available mode. Network and provider response times still affect latency. There is no queue prefetch. |
| A Library item crashes | Check the metadata provider directly. The reported library `loadFeed` crash has no confirmed fix here yet. |
| A nightly package replaced stable | Both channels share the same extension ID. Import a stable package to switch back. |
| Nightly does not update automatically | This is intentional. Download and import the next nightly package manually. |

### Report a bug

Open an [issue][issues] with:

- Echo version and Combine version, including the release channel.
- Metadata provider and audio providers, with their versions and priority order.
- First Available mode: on or off.
- Exact track title, artist, and requested version.
- Reproduction steps and whether the same track works directly in the provider.
- Relevant error text or a stack trace.

Remove account identifiers, authorization headers, cookies, and URL tokens from logs before posting them.

## Build from source

### Requirements

- JDK **21**.
- Android SDK platform **36** for an Android package.
- Android SDK build tools **35.0.0** for the configured Android Gradle Plugin.
- Node.js for the repository automation tests.
- Git and network access for the first Gradle dependency download.

The Gradle wrapper supplies Gradle. There is no need to install Gradle separately.
The extension package declares minimum Android API 24. Echo itself can have a higher minimum requirement.

### Get the source

```sh
git clone https://github.com/dutixlf/echo-combine-extension.git
cd echo-combine-extension
git switch nightly
```

Use `git switch main` for stable source. Keep full Git history: the build derives its version code from the commit count.

For Android builds, set `ANDROID_HOME` to your SDK directory. Alternatively, set `sdk.dir` in an ignored `local.properties` file.
The Android SDK is not required for the extension JAR or JVM regression tests.

### Run checks

```sh
bash .github/scripts/test-release-metadata.sh
node .github/scripts/test-autoassign.mjs
bash ./gradlew :ext:check :ext:shadowJar
```

The regression suite uses fake providers, not live accounts. It covers routing exclusions, matching, version labels, fallback, priority, serialization, and cancellation.

Passing tests do not replace playback confirmation with real providers.

### Build an Android package

```sh
bash ./gradlew :ext:check :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
Rename or copy the APK with an `.eapk` extension for import into Echo. Local debug packages use Android's debug signing configuration.

### Build a locally labeled nightly

```sh
bash ./gradlew :ext:check :ext:shadowJar :app:assembleDebug \
  "-PextVersionName=nightly-$(git rev-parse --short=7 HEAD)" \
  "-PextName=Combine Extension Nightly" \
  "-PextUpdateUrl="

cp app/build/outputs/apk/debug/app-debug.apk app/build/echo_combine-nightly-local.eapk
```

The version override applies to both APK metadata and the extension JAR. Without this override, local builds use the `v<commit>` label regardless of the checked-out branch.

To copy the package to an ADB-connected phone:

```sh
adb push app/build/echo_combine-nightly-local.eapk /sdcard/Download/
```

Import the file from the phone's Downloads folder through Echo's extension manager.

## CI and release workflow

| Workflow | Trigger | Result |
| --- | --- | --- |
| [`test.yml`](.github/workflows/test.yml) | Pushes and pull requests | Automation checks, JVM tests, and extension JAR build. |
| [`build.yml`](.github/workflows/build.yml) | Push to `main` or `nightly`, manual run, daily schedule | Tests, signed `.eapk`, workflow artifact, and GitHub release. |
| [`autoassign.yml`](.github/workflows/autoassign.yml) | New or reopened issues and pull requests | Assign unassigned items to `dutixlf`. |

Build badges show the last **push-triggered** release build for each branch. They do not report the daily scheduled run.
Badges can show an unknown status before their branch or workflow is published.

### Stable and nightly publishing

- A push to `main` publishes `v<commit>` as a normal release and marks it latest.
- A push to `nightly` publishes `nightly-<commit>` as a prerelease with `make_latest: false`.
- The daily job is scheduled for **00:00 UTC** and checks out `nightly`, not `main`.
- GitHub can delay scheduled runs. The workflow must exist on the default branch for schedules to activate.
- A manual run supports either source branch.
- Rebuilding the same commit updates its existing release. It does not create a new daily version.
- Release assets identify their source commit. Old releases are not automatically deleted.

### Repository setup

1. Enable GitHub Actions for the repository.
2. Publish the `nightly` branch.
3. Add the workflows and helper scripts to the default branch, `main`.
4. Set the `KEYSTORE_B64` and `PASSWORD` repository secrets for signed release builds.

`KEYSTORE_B64` contains the base64-encoded signing keystore. `PASSWORD` supplies the store and key password. The configured key alias is `key0`.

The release workflow requests `contents: write` and removes its generated signing key after the job.

Autoassignment preserves existing assignees. Its privileged workflow only uses event metadata. It never checks out or executes pull request code.
Fork maintainers can change the assignee in `autoassign.yml`.

### Development flow

Develop and test on `nightly`. Confirm playback with real providers before merging approved changes into `main` for a stable release.

[echo]: https://github.com/brahmkshatriya/echo
[issues]: https://github.com/dutixlf/echo-combine-extension/issues
[stable-download]: https://github.com/dutixlf/echo-combine-extension/releases/latest
[nightly-download]: https://github.com/dutixlf/echo-combine-extension/releases?q=nightly&expanded=true
[stable-runs]: https://github.com/dutixlf/echo-combine-extension/actions/workflows/build.yml?query=branch%3Amain+event%3Apush
[nightly-runs]: https://github.com/dutixlf/echo-combine-extension/actions/workflows/build.yml?query=branch%3Anightly+event%3Apush
[test-runs]: https://github.com/dutixlf/echo-combine-extension/actions/workflows/test.yml?query=branch%3Anightly
[build-docs]: #build-from-source
[stable-release-badge]: https://img.shields.io/github/v/release/dutixlf/echo-combine-extension?label=stable&sort=date&color=2ea44f
[nightly-channel-badge]: https://img.shields.io/badge/nightly-prerelease-f59e0b
[stable-build-badge]: https://img.shields.io/github/actions/workflow/status/dutixlf/echo-combine-extension/build.yml?branch=main&event=push&label=stable%20build
[nightly-build-badge]: https://img.shields.io/github/actions/workflow/status/dutixlf/echo-combine-extension/build.yml?branch=nightly&event=push&label=nightly%20build
[tests-badge]: https://img.shields.io/github/actions/workflow/status/dutixlf/echo-combine-extension/test.yml?branch=nightly&event=push&label=tests
[android-badge]: https://img.shields.io/badge/platform-Android-3ddc84?logo=android&logoColor=white
[jdk-badge]: https://img.shields.io/badge/build-JDK%2021-007396
