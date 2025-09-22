#!/bin/bash
set -xeuo pipefail

# Check for an image tag argument being passed, if not check for an environment variable
# When neither are present, run git cliff to determine the latest tag
VERSION="${1:-${VERSION:-$(git cliff --bumped-version)}}"

# Ensure we are in the top level directory of the repository
if [[ ! -d ".git" ]]; then
  echo "This script must be run from the root of the repository."
  exit 1
fi

# Strip a possible 'v' prefix from the version
VERSION=${VERSION#v}

DOCKER_IMAGE_FILES=(
  "docker-compose/docker-compose.yml"
)

for file in "${DOCKER_IMAGE_FILES[@]}"; do
  if [[ -f "$file" ]]; then
    echo "Updating image tag in $file to $VERSION"
    # Fixed sed command with proper escaping and simpler pattern
    sed -i "s|\(kvasir/kvasir-server/.*\):.*|\1:$VERSION|g" "$file"
  else
    echo "File $file does not exist, skipping."
  fi
done

# Update version and AppVersion of Helm chart
HELM_CHART_FILES=(
  "kubernetes/kvasir/Chart.yaml"
)
for file in "${HELM_CHART_FILES[@]}"; do
  if [[ -f "$file" ]]; then
    echo "Updating Helm chart version in $file to $VERSION"
    sed -i "s|^version:.*|version: $VERSION|g" "$file"
    sed -i "s|^appVersion:.*|appVersion: \"$VERSION\"|g" "$file"
  else
    echo "Helm chart file $file does not exist, skipping."
  fi
done

HELM_VALUES_FILES=(
  "kubernetes/kvasir/values.yaml"
)
for file in "${HELM_VALUES_FILES[@]}"; do
  if [[ -f "$file" ]]; then
    echo "Updating Helm values file $file to $VERSION"
    sed -e 's/^[[:blank:]]*$/# __NEWLINE__#/' -i "$file"
    yq ".global.image.tag = \"$VERSION\"" -i "$file"
    sed -e 's/.*# __NEWLINE__#.*//' -i "$file"
  else
    echo "Helm values file $file does not exist, skipping."
  fi
done

# Update mvn versions and don't error when version hasnt been changed
./mvnw --batch-mode versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false

# Bump version file of UI
# insert with EOF into services/ui/src/environments/environment.prod.ts

cat <<EOF >services/ui-service/src/main/frontend/src/environments/version.prod.ts
  export const version = '${VERSION}';
  export const buildDate = '$(date -u +%Y-%m-%dT%H:%M:%SZ)';
EOF

echo "Version bump completed successfully to $VERSION"
