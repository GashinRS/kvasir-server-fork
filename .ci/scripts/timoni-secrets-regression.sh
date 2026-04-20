#!/usr/bin/env bash
# .ci/scripts/timoni-secrets-regression.sh
#
# Regression suite for the Timoni kvasir module's secret-management framework.
# Exercises every supported resolution path (managed, existingSecret, per-field
# ref, mixed) and every validation gate (inline-without-source, manage+existing
# conflict, manage-empty).
#
# Two test phases:
#   1. Vet matrix      — `timoni mod vet` on positive fixtures (must pass).
#   2. Build assertions — `timoni build` on positive fixtures, grep rendered
#                        YAML for expected Secret presence/absence and
#                        secretKeyRef shapes; `timoni build` on negative
#                        fixtures, assert non-zero exit + expected error
#                        substring.
#
# Test fixtures live in timoni/kvasir/test-values/ — see the README there.
#
# Usage: .ci/scripts/timoni-secrets-regression.sh
# Requires: timoni on PATH.

set -euo pipefail

MODULE="timoni/kvasir"
FIXTURES="${MODULE}/test-values"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

PASS=0
FAIL=0

# pass/fail helpers — prefixes mirror the colour-free convention used by the
# rest of .ci/scripts so output stays grep-friendly in GitLab job logs.
pass() { echo "PASS: $*"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $*" >&2; FAIL=$((FAIL + 1)); }

vet_pass() {
	local fixture="$1"
	local out="${TMPDIR}/vet-${fixture}.log"
	if timoni mod vet "$MODULE" --values "${FIXTURES}/${fixture}.cue" >"$out" 2>&1; then
		# Vet exits 0 even on validation failures (logs to stderr instead),
		# so additionally assert no validation error was emitted.
		if grep -qE 'validation failed|validate(Secrets|SecretModes)\.[a-z_]+' "$out"; then
			fail "vet ${fixture}: unexpected validation error"
			sed 's/^/    /' "$out" >&2
		else
			pass "vet ${fixture}"
		fi
	else
		fail "vet ${fixture}: non-zero exit"
		sed 's/^/    /' "$out" >&2
	fi
}

build_assert() {
	local fixture="$1"
	shift
	# Remaining args alternate: '+pattern' (must appear) or '-pattern' (must not appear).
	local out="${TMPDIR}/build-${fixture}.yaml"
	if ! timoni build default "$MODULE" --values "${FIXTURES}/${fixture}.cue" >"$out" 2>&1; then
		fail "build ${fixture}: non-zero exit"
		sed 's/^/    /' "$out" >&2
		return
	fi
	local ok=1
	for spec in "$@"; do
		local op="${spec:0:1}"
		local pattern="${spec:1}"
		if [ "$op" = "+" ]; then
			if ! grep -qE "$pattern" "$out"; then
				fail "build ${fixture}: missing expected pattern: ${pattern}"
				ok=0
			fi
		elif [ "$op" = "-" ]; then
			if grep -qE "$pattern" "$out"; then
				fail "build ${fixture}: forbidden pattern present: ${pattern}"
				ok=0
			fi
		else
			fail "build ${fixture}: malformed assertion spec: ${spec}"
			ok=0
		fi
	done
	if [ "$ok" = 1 ]; then
		pass "build ${fixture}"
	fi
}

build_fail() {
	local fixture="$1"
	local expected_substr="$2"
	local out="${TMPDIR}/build-${fixture}.log"
	if timoni build default "$MODULE" --values "${FIXTURES}/${fixture}.cue" >"$out" 2>&1; then
		fail "build ${fixture}: expected non-zero exit but got success"
		return
	fi
	if grep -qF "$expected_substr" "$out"; then
		pass "build ${fixture}: failed with expected message"
	else
		fail "build ${fixture}: failed but expected substring not found: ${expected_substr}"
		sed 's/^/    /' "$out" >&2
	fi
}

echo "==> Phase 1: vet positive fixtures"
vet_pass pos-managed
vet_pass pos-existing-secret
vet_pass pos-perfield-ref
vet_pass pos-mixed

echo "==> Phase 2: build assertions on positive fixtures"

# pos-managed: 3 module-managed Secrets, sensitive ConfigMap entries scrubbed,
# secretKeyRef wired for every registered field.
build_assert pos-managed \
	'+kind: Secret' \
	'+name: default-keycloak-secret' \
	'+name: default-s3-secret' \
	'+name: default-clickhouse-secret' \
	'+secretKeyRef'

# Sensitive values must appear in the managed Secrets' stringData but must NOT
# leak into the ConfigMap. Scope the scrub assertion to the ConfigMap chunk
# only (awk slices between `kind: ConfigMap` and the next document separator).
managed_cm="${TMPDIR}/build-pos-managed-configmap.yaml"
awk '/^kind: ConfigMap/,/^---/' "${TMPDIR}/build-pos-managed.yaml" >"$managed_cm"
for forbidden in test-admin-client-secret test-admin-password test-ak test-sk test-password; do
	if grep -qF "$forbidden" "$managed_cm"; then
		fail "ConfigMap leak: '${forbidden}' present in pos-managed ConfigMap"
	else
		pass "ConfigMap scrubbed of '${forbidden}'"
	fi
done

# pos-existing-secret: NO managed Secret rendered; env refs target test-* Secrets.
build_assert pos-existing-secret \
	'-name: default-keycloak-secret' \
	'-name: default-s3-secret' \
	'-name: default-clickhouse-secret' \
	'+name: test-keycloak' \
	'+name: test-s3' \
	'+name: test-clickhouse' \
	'+secretKeyRef'

# pos-perfield-ref: NO managed Secret; per-field refs to platform-* Secret + custom
# upstream key names (AWS_ACCESS_KEY_ID) propagate into env refs.
build_assert pos-perfield-ref \
	'-name: default-keycloak-secret' \
	'-name: default-s3-secret' \
	'-name: default-clickhouse-secret' \
	'+name: platform-keycloak-admin' \
	'+name: test-s3-vault-style' \
	'+key: AWS_ACCESS_KEY_ID' \
	'+key: AWS_SECRET_ACCESS_KEY'

echo "==> Phase 3: build negative fixtures (must fail with specific messages)"
build_fail neg-inline-no-source     'sets sensitive field'
build_fail neg-manage-and-existing  'cannot be combined with `existingSecret`'
build_fail neg-manage-empty         'requires at least one inline value'

echo
echo "Summary: ${PASS} passed, ${FAIL} failed"
[ "$FAIL" -eq 0 ]
