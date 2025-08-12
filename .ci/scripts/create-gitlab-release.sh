#!/bin/bash
set -xeuo pipefail

# Source the release environment variables
source release.env

echo "Creating GitLab release..."

# Read release notes content
RELEASE_NOTES=$(cat release_notes.md)

# First, create the tag if it doesn't exist
echo "Creating tag ${RELEASE_TAG}..."
curl -X POST \
  -H "PRIVATE-TOKEN: $GIT_PUSHER_TOKEN" \
  -H "Content-Type: application/json" \
  "$CI_API_V4_URL/projects/$CI_PROJECT_ID/repository/tags" \
  -d "{
    \"tag_name\": \"${RELEASE_TAG}\",
    \"ref\": \"$CI_COMMIT_SHA\",
    \"message\": \"Release ${RELEASE_TAG}\"
  }"

# Then create the release
echo "Creating release ${RELEASE_TAG}..."
curl -X POST \
  -H "PRIVATE-TOKEN: $GIT_PUSHER_TOKEN" \
  -H "Content-Type: application/json" \
  "$CI_API_V4_URL/projects/$CI_PROJECT_ID/releases" \
  -d "{
    \"name\": \"Release ${RELEASE_TAG}\",
    \"tag_name\": \"${RELEASE_TAG}\",
    \"description\": $(echo "$RELEASE_NOTES" | jq -R -s .)
  }"
