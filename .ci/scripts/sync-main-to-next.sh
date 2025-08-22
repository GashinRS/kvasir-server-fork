#!/bin/bash
set -xeuo pipefail

echo "Syncing main back to next via fast-forward merge..."

git config --global user.email "${GITLAB_USER_EMAIL}"
git config --global user.name "GitLab CI Bot"
git remote set-url origin "https://gitlab-ci-token:${GIT_PUSHER_TOKEN}@${CI_SERVER_HOST}/${CI_PROJECT_PATH}.git"

# Fetch the latest state from the remote
git fetch origin

# Checkout the next branch
git checkout next

# Pull the latest changes for next to ensure we are up to date
git pull origin next

# Attempt a fast-forward merge from main.
# This will fail if 'next' has diverged, which is a safety check.
echo "Attempting to fast-forward merge origin/main into next..."
git merge --ff-only origin/main

# Push the updated next branch
echo "Pushing updated next branch..."
git push origin next

echo "✅ Successfully synced main to next."
