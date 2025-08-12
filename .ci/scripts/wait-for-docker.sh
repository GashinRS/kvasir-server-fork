#!/bin/bash
set -xeuo pipefail

timeout=30
start_time=$(date +%s)

while ! docker info >/dev/null 2>&1; do
  if [ $(( $(date +%s) - start_time )) -ge $timeout ]; then
    echo "Timeout waiting for Docker daemon to be ready." >&2
    exit 1
  fi
  sleep 1
done
