#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
hash=$(git rev-parse --short=7 HEAD)
sha=$(git rev-parse HEAD)

for branch in main nightly; do
  env_file="$tmp/$branch.env"
  BUILD_BRANCH="$branch" GITHUB_ENV="$env_file" RUNNER_TEMP="$tmp" \
    bash .github/scripts/prepare-release.sh
  grep -Fxq "COMMIT_SHA=$sha" "$env_file"
  grep -Fq "Commit: \`$sha\`" "$tmp/release-notes.txt"
  if [[ "$branch" == main ]]; then
    grep -Fxq "VERSION=v$hash" "$env_file"
    grep -Fxq "APP_PATH=app/build/echo_combine-v$hash.eapk" "$env_file"
    grep -Fxq 'EXT_NAME=Combine Extension' "$env_file"
    grep -Fxq "$(grep '^extUpdateUrl=' gradle.properties | sed 's/^extUpdateUrl=/UPDATE_URL=/')" "$env_file"
  else
    grep -Fxq "VERSION=nightly-$hash" "$env_file"
    grep -Fxq "APP_PATH=app/build/echo_combine-nightly-$hash.eapk" "$env_file"
    grep -Fxq 'EXT_NAME=Combine Extension Nightly' "$env_file"
    grep -Fxq 'UPDATE_URL=' "$env_file"
  fi
  echo "PASS: $branch release metadata"
done

if BUILD_BRANCH=unexpected GITHUB_ENV="$tmp/invalid.env" RUNNER_TEMP="$tmp" \
    bash .github/scripts/prepare-release.sh 2> "$tmp/error.txt"; then
  echo 'FAIL: unexpected release branch accepted' >&2
  exit 1
fi
grep -Fq 'Unsupported release branch' "$tmp/error.txt"
echo 'PASS: unexpected branches rejected'
