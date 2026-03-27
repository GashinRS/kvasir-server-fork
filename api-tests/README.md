# Kvasir API Tests

HTTP integration tests for the Kvasir API written in [Hurl](https://hurl.dev).
Each file is a fully self-contained scenario: it registers its own pod, acquires
a token, exercises a feature area, and deletes the pod at the end.

## Prerequisites

| Tool                                            | Minimum | Install                              |
| ----------------------------------------------- | ------- | ------------------------------------ |
| [hurl](https://hurl.dev/docs/installation.html) | 4.0     | see below                            |
| [just](https://github.com/casey/just)           | 1.0     | `cargo install just`                 |
| A running Kvasir stack                          | —       | `cd compose && docker compose up -d` |

**Install hurl:**

```bash
VERSION=7.1.0
curl -sL https://github.com/Orange-OpenSource/hurl/releases/download/$VERSION/hurl-$VERSION-x86_64-unknown-linux-gnu.tar.gz \
  | tar xz -C /usr/local/bin --strip-components=1
```

**Install hurl (Windows):** `scoop install hurl` or `winget install hurl`

## Setup

Copy the environment template and fill in your URLs (if they differ from the defaults):

```bash
cp api-tests/hurl.env.example api-tests/hurl.env
```

`hurl.env` is git-ignored — never commit it. It contains only URLs; the sole
runtime variable (`ts`) is injected automatically by the `just` recipes.

**`hurl.env` contents:**

```env
keycloak_url=http://localhost:28280
realm=quarkus
kvasir_url=http://localhost:28080
kss_vocab=https://kvasir.discover.ilabt.imec.be/vocab#
```

## Running the tests

```bash
just api-test                # run all scenarios
just api-test --verbose      # full request/response detail
just api-test-verbose        # shortcut for above
just api-test-report         # generate HTML report under api-tests/reports/
```

Hurl runs `api-tests/` as a directory — it discovers and runs all `*.hurl`
files in lexicographic order. No script wrapper needed.

By default, tests run in parallel (up to 4 at a time) for speed. Use `--jobs 1` to run sequentially

### HTML Report

Generated into `api-tests/reports/<timestamp>/index.html` (gitignored).
Serve via HTTP to preserve relative links:

```bash
python3 -m http.server 8765 --directory api-tests/reports/<timestamp>
```

## Test scenarios

Each scenario uses a distinct named user (suffixed with `{{ts}}` for uniqueness)
and cleans up its own pod at the end.

| File                      | User  | Coverage                                                                       |
| ------------------------- | ----- | ------------------------------------------------------------------------------ |
| `01-pod-lifecycle.hurl`   | alice | Register pod → verify JSON-LD config → delete pod                              |
| `02-knowledge-graph.hurl` | bob   | Insert JSON-LD entity → poll until `COMMITTED` → GraphQL query → delete pod    |
| `03-slices.hurl`          | carol | Insert entity → create slice → query through slice → delete slice → delete pod |
| `04-s3.hurl`              | dave  | Upload file → list bucket → download → delete file → delete pod                |

## How it works

Each file follows the same self-contained pattern:

```
POST /                        Register pod (no auth needed)
POST keycloak/.../token       client_credentials grant (retry until provisioned)
GET  /{podId}                 Wait for pod initialisation (retry until 200)
  ... scenario-specific requests ...
DELETE /{podId}               Cleanup (Kvasir also removes the Keycloak user)
```

**Only `ts` is injected at runtime** (via `just`). The `adminClientSecret` is
a fixed `test-secret` — safe for tests since each client ID is unique per run
(`alice-{{ts}}`, `bob-{{ts}}`, etc.).

**No Keycloak admin API calls.** `POST /` is `@PermitAll` and accepts
`adminClientId` + `adminClientSecret` + `ownerUserId`. Kvasir's initialiser
creates the Keycloak service-account client and user automatically.

## CI

The `smoke_test:compose` GitLab CI job (`.ci/test.yml`) runs these tests
against the full Docker Compose stack. It uses `api-tests/hurl.env.ci`
(committed, no secrets) which points to the Docker-in-Docker service addresses:

- `kvasir_url=http://docker:28080`
- `keycloak_url=http://docker:28280`

## Troubleshooting

Run `just api-test --verbose` to see the full HTTP exchange for any failing request.
Inspecting the generated HTML report can also be helpful, as it includes all requests and responses
