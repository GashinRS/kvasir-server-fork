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

| File                          | Scenario                                                                                                   |
|-------------------------------|------------------------------------------------------------------------------------------------------------|
| `pos-managed.cue`             | `mode: "managed"` for every integration with inline values → module-managed Secret per integration.        |
| `pos-existing-secret.cue`     | `mode: "existing"` + `secretName` per integration; no inline sensitive values → no managed Secret rendered.|
| `pos-perfield-ref.cue`        | Per-field `ref` overrides on top of `mode: "existing"` → no managed Secret, env refs cross-Secret.         |
| `pos-mixed.cue`               | Mix: keycloak `mode: "existing"` + per-field `ref`, s3 `mode: "none"`, clickhouse `mode: "managed"`.       |

## Negative cases (expected: vet fails with a specific message)

| File                          | Expected error fragment                                                                       |
|-------------------------------|-----------------------------------------------------------------------------------------------|
| `neg-manage-empty.cue`        | `is set to 'managed' mode, but field .* is missing its required 'inlineValue'`                |

## Render-assertion cases (used by build-grep step)

`pos-managed.cue`, `pos-existing-secret.cue`, and `pos-perfield-ref.cue` are
also rendered by `timoni build` and grepped for expected `Secret` presence/absence
and `secretKeyRef` shapes.
