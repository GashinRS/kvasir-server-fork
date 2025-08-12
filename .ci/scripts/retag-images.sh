#!/bin/bash
set -xeuo pipefail

SOURCE_TAG="${CI_DEFAULT_BRANCH}-${CI_COMMIT_SHORT_SHA}"
echo "Retagging images from $SOURCE_TAG to $RELEASE_TAG"

echo "Discovering services to retag..."
SERVICES=""

for service_dir in services/*/; do
  if [ -f "${service_dir}pom.xml" ]; then
    ARTIFACT_ID=$(./mvnw -f "${service_dir}pom.xml" help:evaluate -Dexpression=project.artifactId -q -DforceStdout 2>/dev/null || echo "")

    if [ -n "$ARTIFACT_ID" ]; then
      SERVICES="$SERVICES $ARTIFACT_ID"
      echo "Found service: $ARTIFACT_ID (from ${service_dir})"
    fi
  fi
done

echo "Services to retag: $SERVICES"

SUCCESS_COUNT=0
TOTAL_COUNT=0

for service in $SERVICES; do
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  echo "Processing service: $service"

  SOURCE_IMAGE="$CI_REGISTRY_IMAGE/$service:$SOURCE_TAG"
  TARGET_IMAGE="$CI_REGISTRY_IMAGE/$service:$RELEASE_TAG"
  LATEST_IMAGE="$CI_REGISTRY_IMAGE/$service:latest"

  if docker manifest inspect "$SOURCE_IMAGE" >/dev/null 2>&1; then
    echo "Retagging $service..."
    docker pull "$SOURCE_IMAGE"
    docker tag "$SOURCE_IMAGE" "$TARGET_IMAGE"
    docker tag "$SOURCE_IMAGE" "$LATEST_IMAGE"
    docker push "$TARGET_IMAGE"
    docker push "$LATEST_IMAGE"
    echo "✅ Successfully retagged $service"
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
  else
    echo "⚠️  Image $SOURCE_IMAGE not found, skipping $service"
  fi
done

echo "Retagging complete: $SUCCESS_COUNT/$TOTAL_COUNT services successfully retagged"
