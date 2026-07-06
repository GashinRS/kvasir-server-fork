# test-values

Module-level test fixtures consumed by the `timoni:lint` CI job (see
`.ci/timoni.yml`) and by local regression checks.

Each file is a `--values` overlay for `timoni mod vet timoni/kvasir` /
`timoni build … timoni/kvasir`. They intentionally have **no `package`
declaration** so they are never auto-loaded as part of the module package — they
only apply when passed explicitly via `--values`.

This directory is excluded from the published OCI artifact via
[`../timoni.ignore`](../timoni.ignore).

## Positive cases (expected: vet succeeds, build emits expected shape)

| File                       | Scenario                                                                           |
|----------------------------|------------------------------------------------------------------------------------|
| `pos-existing-secret.cue`  | `secretName` per integration → env refs to existing Secrets, no managed Secret.    |
| `pos-field-secretname.cue` | Field-level `secretName` (no key) + mixed managed/existing within same integration.|
| `pos-key-override.cue`     | Per-field `key` overrides → uses integration's secretName with custom key names.   |
| `pos-managed.cue`          | Per-field `value` for every integration → module-managed Secret per integration.   |
| `pos-mixed.cue`            | Mix: keycloak existing + per-field override, s3 unconfigured, clickhouse managed.  |
| `pos-perfield-ref.cue`     | Per-field `secretName` + `key` override → env refs cross-Secret.                   |

## Render-assertion cases (used by CI script)

`pos-managed.cue`, `pos-existing-secret.cue`, and `pos-perfield-ref.cue` are
rendered by `timoni build` in `.ci/scripts/timoni-secrets-regression.sh` and
grepped for expected `Secret` presence/absence and `secretKeyRef` shapes.
