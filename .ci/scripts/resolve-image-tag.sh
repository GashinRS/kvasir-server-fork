#!/usr/bin/env bash
# resolve-image-tag.sh
#
# Resolve a usable image tag from the project's container registry by probing a
# fallback chain (IMAGE_TAG → CI_COMMIT_REF_SLUG → next). Prints the first tag
# for which `docker manifest inspect "<repo>:<tag>"` succeeds for *every* image
# passed in.
#
# Usage:
#   resolve-image-tag.sh <image> [<image> ...]
#
# Each <image> is a registry path WITHOUT a tag, e.g.
#   "$CI_REGISTRY/$CI_PROJECT_PATH/monolith"
#   "$CI_REGISTRY/$CI_PROJECT_PATH/kg-query-api"
#
# Output: the resolved tag on stdout (single line, no trailing newline beyond echo).
# Exit codes:
#   0  — a tag was found that exists for all requested images
#   1  — usage error
#   2  — no candidate tag exists for all requested images
#
# Why a single tag for many images: all kvasir service images are built and
# tagged together by `package:maven`, so they share the same tag set in the
# registry. Resolving once keeps callers simple when we move from monolith to
# per-service deployments.
#
# Why `next` and not `main` or `latest`: image publishing is gated to MR
# pipelines and semver tag pipelines — direct pushes to main never build an
# image. The `next` tag is published by the Releasaurus release MR's pipeline
# (see workflow rule for `releasaurus-release-*` in .gitlab-ci.yml) and tracks
# the latest release-candidate build. `latest` only moves on tag pipelines, so
# falling back to it would test against an old released version.
#
# Required env: none. Honored env: IMAGE_TAG, CI_COMMIT_REF_SLUG.

set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <image> [<image> ...]" >&2
  exit 1
fi

images=("$@")
candidates=("${IMAGE_TAG:-}" "${CI_COMMIT_REF_SLUG:-}" "next")

for tag in "${candidates[@]}"; do
  [ -z "$tag" ] && continue
  all_found=true
  for image in "${images[@]}"; do
    if ! docker manifest inspect "${image}:${tag}" > /dev/null 2>&1; then
      all_found=false
      break
    fi
  done
  if $all_found; then
    echo "$tag"
    exit 0
  fi
done

echo "ERROR: no tag from [${candidates[*]}] exists for all images: ${images[*]}" >&2
exit 2
