#!/bin/bash
set -xeuo pipefail

echo "Extracting release notes from the release commit message..."

# Get the release version from the commit message
COMMIT_TITLE=$(echo "$CI_COMMIT_MESSAGE" | head -n 1)
RELEASE_VERSION=$(echo "$COMMIT_TITLE" | sed -En "$VERSION_REGEX")

if [ -z "$RELEASE_VERSION" ]; then
  echo "❌ Could not extract version from commit title: $COMMIT_TITLE"
  exit 1
fi

echo "Detected release version: $RELEASE_VERSION"

# The release notes are embedded in the merge commit message.
# We extract the content between the '---' markers.
echo "Extracting changelog from commit message..."
echo "$CI_COMMIT_MESSAGE" | awk '
  BEGIN { in_changelog = 0; found_first_separator = 0 }
  /^---$/ {
    if (!found_first_separator) {
      found_first_separator = 1
      in_changelog = 1
      next
    } else {
      in_changelog = 0
      exit
    }
  }
  in_changelog && !/^[[:space:]]*$/ { print }
' > release_notes.md

# Export the release tag for other scripts
export RELEASE_TAG="v${RELEASE_VERSION}"
echo "RELEASE_TAG=${RELEASE_TAG}" > release.env

echo "Release tag for other jobs: ${RELEASE_TAG}"

if [ -s "release_notes.md" ]; then
  echo "✅ Successfully extracted release notes from commit message."
  echo "Release notes content:"
  cat release_notes.md
else
  echo "⚠️ Could not extract changelog from commit message, using fallback."
  echo "Release v${RELEASE_VERSION}" > release_notes.md
fi
