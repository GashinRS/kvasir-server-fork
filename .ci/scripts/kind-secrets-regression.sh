#!/usr/bin/env bash
# .ci/scripts/kind-secrets-regression.sh
#
# Real-cluster regression for the secret-management framework. Run AFTER the
# main verify:kind flow has stood up the kind cluster + dependencies + the
# primary kvasir instance.
#
# Strategy: server-side dry-run only. We pre-create the companion Secrets that
# kind/values-kind-secrets-test.cue references, then `timoni apply --dry-run`
# the secrets-test instance against the live apiserver. This validates:
#   - rendered manifests are accepted by the apiserver,
#   - referenced Secrets exist,
#   - the Deployment env block contains the expected secretKeyRef shape.
#
# We deliberately do NOT spin up a second long-running Kvasir Pod: this script
# tests CUE→cluster wiring, not a second Kvasir runtime (which would need its
# own DB/keycloak/etc.).
#
# Usage: .ci/scripts/kind-secrets-regression.sh
# Requires: kubectl, timoni, mise-managed envs already loaded.

set -euo pipefail

NAMESPACE="kvasir"
INSTANCE="kvasir-secrets-test"
VALUES="kind/values-kind-secrets-test.cue"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

PASS=0
FAIL=0
pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*" >&2; FAIL=$((FAIL + 1)); }

echo "==> Pre-creating companion Secrets referenced by ${VALUES}"

# Secret values are dummies — server-side dry-run only validates references and
# manifest shape, not authenticated round-trips against keycloak/s3.
kubectl -n "$NAMESPACE" create secret generic kind-keycloak-creds \
	--from-literal=admin-username=admin \
	--from-literal=admin-password=admin \
	--from-literal=admin-client-id=admin-cli \
	--from-literal=admin-client-secret=dummy \
	--dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$NAMESPACE" create secret generic kind-keycloak-shared-clientsecret \
	--from-literal=client-secret=dummy \
	--dry-run=client -o yaml | kubectl apply -f -

# s3 secret uses Vault-style upstream key names (matches fields.<f>.key in the
# values file).
kubectl -n "$NAMESPACE" create secret generic kind-s3-creds-vault-style \
	--from-literal=AWS_ACCESS_KEY_ID=dummy \
	--from-literal=AWS_SECRET_ACCESS_KEY=dummy \
	--dry-run=client -o yaml | kubectl apply -f -

echo "==> Server-side dry-run of secrets-test instance"
dryrun_log="${TMPDIR}/dryrun.log"
if ! timoni -n "$NAMESPACE" apply "$INSTANCE" timoni/kvasir \
		--values "$VALUES" --dry-run >"$dryrun_log" 2>&1; then
	fail "timoni apply --dry-run failed"
	sed 's/^/    /' "$dryrun_log" >&2
	exit 1
fi
pass "timoni apply --dry-run accepted by apiserver"

echo "==> Rendering manifests for shape assertions"
rendered="${TMPDIR}/rendered.yaml"
timoni build "$INSTANCE" timoni/kvasir --values "$VALUES" >"$rendered"

# Extract just the Deployment for env-ref checks; kubectl can parse multi-doc
# input but greps on slices keep the failure messages local to the relevant
# resource.
deployment="${TMPDIR}/deployment.yaml"
awk '/^kind: Deployment/,/^---/' "$rendered" >"$deployment"

assert_in_deployment() {
	local pattern="$1"
	local desc="$2"
	if grep -qE "$pattern" "$deployment"; then
		pass "Deployment env: $desc"
	else
		fail "Deployment env missing: $desc (pattern: $pattern)"
	fi
}

# keycloak: existingSecret kind-keycloak-creds with default keys, BUT
# adminClientSecret overridden to kind-keycloak-shared-clientsecret/client-secret.
assert_in_deployment 'name: kind-keycloak-creds'                 "ref keycloak existing secret"
assert_in_deployment 'name: kind-keycloak-shared-clientsecret'   "per-field ref to shared keycloak clientsecret"

# s3: existingSecret kind-s3-creds-vault-style with custom upstream key names.
assert_in_deployment 'name: kind-s3-creds-vault-style'           "ref s3 vault-style secret"
assert_in_deployment 'key: AWS_ACCESS_KEY_ID'                    "s3 custom access-key field key"
assert_in_deployment 'key: AWS_SECRET_ACCESS_KEY'                "s3 custom secret-key field key"

# clickhouse: not configured in values file → no source needed (no inline
# values, no manage, no existingSecret). Assert no managed Secret was emitted
# for this instance.
if grep -qE "name: ${INSTANCE}-clickhouse-secret" "$rendered"; then
	fail "unexpected module-managed clickhouse Secret rendered"
else
	pass "no module-managed clickhouse Secret (as expected)"
fi

echo
echo "Summary: ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ]
