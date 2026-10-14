#!/usr/bin/env bash
set -euo pipefail

: "${BUILD_BRANCH:?Set BUILD_BRANCH to main or nightly}"
: "${GITHUB_ENV:?Set GITHUB_ENV}"
: "${RUNNER_TEMP:?Set RUNNER_TEMP}"

hash=$(git rev-parse --short=7 HEAD)
sha=$(git rev-parse HEAD)
id=$(grep '^extId=' gradle.properties | cut -d'=' -f2-)
name=$(grep '^extName=' gradle.properties | cut -d'=' -f2-)
update_url=$(grep '^extUpdateUrl=' gradle.properties | cut -d'=' -f2-)

case "$BUILD_BRANCH" in
  main) version="v$hash" ;;
  nightly)
    version="nightly-$hash"
    name="$name Nightly"
    # Echo resolves GitHub update URLs to releases/latest, which excludes prereleases.
    update_url=""
    ;;
  *) echo "Unsupported release branch: $BUILD_BRANCH" >&2; exit 1 ;;
esac

{
  printf 'VERSION=%s\nCOMMIT_SHA=%s\nEXT_NAME=%s\nUPDATE_URL=%s\n' "$version" "$sha" "$name" "$update_url"
  printf 'APP_PATH=app/build/%s-%s.eapk\n' "$id" "$version"
  printf 'NOTES_PATH=%s/release-notes.txt\n' "$RUNNER_TEMP"
} >> "$GITHUB_ENV"

{
  printf '## %s\n\nBranch: `%s`\nCommit: `%s`\n\n' "$name" "$BUILD_BRANCH" "$sha"
  git log -1 --format=%B
} > "$RUNNER_TEMP/release-notes.txt"
