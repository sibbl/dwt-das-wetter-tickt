#!/usr/bin/env bash
set -euo pipefail

tag="${1:-}"
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 vMAJOR.MINOR.PATCH" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "The worktree must be clean before releasing." >&2
  exit 1
fi

for asset in \
  store-assets/play-store-icon.png \
  store-assets/feature-graphic.png \
  store-assets/wear-screenshots/01-radar-map.png \
  store-assets/wear-screenshots/02-layer-menu.png \
  PRIVACY.md \
  LICENSE \
  DATA_SOURCES.md; do
  test -f "$asset" || {
    echo "Missing release asset: $asset" >&2
    exit 1
  }
done

APP_VERSION_NAME="${tag#v}" ./gradlew testDebugUnitTest assembleDebug

echo "Release preflight passed for $tag."
