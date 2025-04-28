#!/usr/bin/env bash

set -eo pipefail

# Ensure executing from the workdir of this script
# and return to the original workdir when done
cd "$(dirname "$0")"
trap 'cd -' EXIT

./scripts/create-kind-cluster-with-registry.sh
source ./scripts/set-host-nipio.sh

helmfile sync --environment kind --state-values-set tlsEnabled=true

echo "Kvasir deployed to https://$KVASIR_HOST"
echo "Kvasir UI: https://$KVASIR_HOST/_ui"
echo "Keycloak Admin UI: https://$KEYCLOAK_HOST/auth/admin"
