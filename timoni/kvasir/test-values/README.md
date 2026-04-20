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

| File                          | Scenario                                                                                     |
|-------------------------------|----------------------------------------------------------------------------------------------|
| `pos-managed.cue`             | `manage: true` for every integration with inline values → module-managed Secret per integration. |
| `pos-existing-secret.cue`     | `existingSecret` per integration; no inline sensitive values → no managed Secret rendered.    |
| `pos-perfield-ref.cue`        | Per-field `ref` overrides on top of `existingSecret` → no managed Secret, env refs cross-Secret. |
| `pos-mixed.cue`               | Mix: keycloak `existingSecret` + per-field `ref`, s3 `existingSecret` with custom keys, clickhouse `manage`. |

## Negative cases (expected: vet fails with a specific message)

| File                          | Expected error fragment                                                                       |
|-------------------------------|------------------------------------------------------------------------------------------------|
| `neg-inline-no-source.cue`    | `sets sensitive field … inline`                                                                |
| `neg-manage-and-existing.cue` | ``cannot be combined with `existingSecret```                                                   |
| `neg-manage-empty.cue`        | `requires at least one inline value`                                                           |

## Render-assertion cases (used by build-grep step)

`pos-managed.cue`, `pos-existing-secret.cue`, and `pos-perfield-ref.cue` are
also rendered by `timoni build` and grepped for expected `Secret` presence/absence
and `secretKeyRef` shapes.
