#!/bin/bash
set -xeuo pipefail

echo "Updating changelog for ${RELEASE_TAG}..."

git config --global user.email "${GITLAB_USER_EMAIL}"
git config --global user.name "GitLab CI Bot"
git remote set-url origin "https://gitlab-ci-token:${GIT_PUSHER_TOKEN}@${CI_SERVER_HOST}/${CI_PROJECT_PATH}.git"
git checkout "$CI_DEFAULT_BRANCH"

if [ -f "release_notes.md" ]; then
  CHANGELOG_HEADER=$(head -n 4 CHANGELOG.md)
  EXISTING_ENTRIES=$(tail -n +5 CHANGELOG.md)

  echo "---" >> new_entry.md
  echo "" >> new_entry.md
  cat release_notes.md >> new_entry.md
  echo "" >> new_entry.md

  echo -e "${CHANGELOG_HEADER}\n"
  echo -e "" > CHANGELOG.md
  cat new_entry.md >> CHANGELOG.md
  echo -e "\n${EXISTING_ENTRIES}" >> CHANGELOG.md

  echo "✅ Updated CHANGELOG.md with release notes from MR description"
else
  echo "⚠️ No release notes found, skipping changelog update"
fi

git add CHANGELOG.md
git commit -m "docs(changelog): update for ${RELEASE_TAG} [skip ci]"
git push origin "$CI_DEFAULT_BRANCH"
