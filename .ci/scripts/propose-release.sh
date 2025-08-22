#!/bin/bash
set -xeuo pipefail

git config --global user.email "${GITLAB_USER_EMAIL}"
git config --global user.name "GitLab CI Bot"

# Check if there are any commits to be released
git fetch --tags
COMMIT_CONTEXT=$(git-cliff --unreleased --context)
HAS_COMMITS=$(echo "$COMMIT_CONTEXT" | jq '.[0].commits | length > 0')

if [ "$HAS_COMMITS" != "true" ]; then
  echo "No new conventional commits found on 'next'."
  exit 0
fi

# If there are commits, then proceed
NEXT_VERSION=$(git-cliff --unreleased --bumped-version)
CHANGELOG_BODY=$(git-cliff --unreleased --bump --strip all)
PROPOSAL_BRANCH="bot/release-proposal/${NEXT_VERSION}"
MR_TITLE="${RELEASE_COMMIT} ${NEXT_VERSION}"

# Create the proposal branch directly from next
git checkout -b "$PROPOSAL_BRANCH" origin/next

# Bump versions and commit
./.ci/scripts/bump-versions.sh "$NEXT_VERSION"
git add .
git commit -m "chore(release): propose release ${NEXT_VERSION}"

# Check for an existing MR for this version by title first
ENCODED_MR_TITLE=$(echo -n "$MR_TITLE" | jq -s -R -r @uri)
EXISTING_MR_ID=$(curl --fail --header "PRIVATE-TOKEN: ${GIT_PUSHER_TOKEN}" "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/merge_requests?search=${ENCODED_MR_TITLE}&in=title&state=opened" | jq '.[0].iid')

# Always force push - handles both new and existing branches gracefully
# We can safely force push because the branch is always created fresh from 'next'
# and there shouldn't be any direct changes to it, everything is controlled by CI
echo "Pushing release proposal branch (will create or update as needed)..."
git push --force "https://gitlab-ci-token:${GIT_PUSHER_TOKEN}@${CI_SERVER_HOST}/${CI_PROJECT_PATH}.git" HEAD:"$PROPOSAL_BRANCH"

# Create a changelog template that can be edited in the MR description
# Git-cliff outputs the changelog starting with a --- marker followed by the version title
# Possible improvement: ensure the --- marker is always present
CHANGELOG_TEMPLATE=$(cat <<EOF

**Instructions:** You can edit the changelog below before merging this MR.
The content between the --- markers will be used as the release notes and
then be merged into the main CHANGELOG.md file.

${CHANGELOG_BODY}

---

EOF
)

# Create / Update the MR. We also add a label to filter
# In future we can use different release::alpha, release::beta, etc. labels
# to control resulting release types
if [ "$EXISTING_MR_ID" == "null" ]; then
  echo "Creating new merge request for version ${NEXT_VERSION}."
  MR_RESPONSE=$(curl --request POST \
    --header "PRIVATE-TOKEN: ${GIT_PUSHER_TOKEN}" \
    --header "Content-Type: application/json" \
    --data "{
      \"source_branch\": \"${PROPOSAL_BRANCH}\",
      \"target_branch\": \"main\",
      \"title\": \"${MR_TITLE}\",
      \"description\": $(echo "$CHANGELOG_TEMPLATE" | jq -Rs .),
      \"labels\": \"release\",
      \"remove_source_branch\": true,
      \"squash\": false
    }" \
    "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/merge_requests")
  MR_IID=$(echo "$MR_RESPONSE" | jq '.iid')
else
  echo "Updating existing merge request !${EXISTING_MR_ID} with updated changelog."
  echo "New commits have been added to the release branch."
  # Just update the description, the branch has already been updated by the push
  curl --request PUT \
    --header "PRIVATE-TOKEN: ${GIT_PUSHER_TOKEN}" \
    --header "Content-Type: application/json" \
    --data "{\"description\": $(echo "$CHANGELOG_TEMPLATE" | jq -Rs .)}" \
    "${CI_API_V4_URL}/projects/${CI_PROJECT_ID}/merge_requests/${EXISTING_MR_ID}"
  MR_IID="$EXISTING_MR_ID"
fi

echo "✅ Release proposal complete for version ${NEXT_VERSION}"
if [ "$EXISTING_MR_ID" == "null" ]; then
  echo "📋 Created new MR !${MR_IID}"
else
  echo "📋 Updated existing MR !${MR_IID}"
fi
