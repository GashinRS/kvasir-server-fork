# Kvasir Server – common development tasks
#
# Run `just prereqs` first to verify all required tools are installed.
# Run `just` or `just --list` to see all recipes.
#
#  Global variables ───────────────────────────────────────────────────────────────
dt := datetime("%Y-%m-%d_%H-%M-%S")
ts := datetime("%s")

# ── Setup ─────────────────────────────────────────────────────────────────────

[private]
default:
  just --list --unsorted

# Check all required tools are installed with adequate versions
[group('setup')]
prereqs:
    #!/usr/bin/env sh
    failed=false

    pass() { printf '  \033[32m✓\033[0m %-10s %s\n' "$1" "${2:-}"; }
    warn() { printf '  \033[33m!\033[0m %-10s %s\n' "$1" "${2:-}"; }
    fail() { printf '  \033[31m✗\033[0m %-10s %s\n' "$1" "${2:-}"; failed=true; }

    # $1=installed $2=minimum  — returns 0 if $1 >= $2
    version_gte() {
      awk -v a="$1" -v b="$2" 'BEGIN {
        split(a, av, "."); split(b, bv, ".")
        for (i = 1; i <= 3; i++) {
          if (av[i]+0 > bv[i]+0) exit 0
          if (av[i]+0 < bv[i]+0) exit 1
        }
        exit 0
      }'
    }

    echo ""
    echo "Kvasir Server — prerequisite check"
    echo "────────────────────────────────────────"
    echo ""

    # ── Required ──────────────────────────────────────────────────────────────

    # Java 25+ (maven.compiler.release=25 in pom.xml)
    if command -v java >/dev/null 2>&1; then
      java_full=$(java -version 2>&1 | head -1)
      java_major=$(java -version 2>&1 | grep -oE '"[0-9]+' | tr -d '"' | head -1)
      if [ "$java_major" -ge 25 ] 2>/dev/null; then
        pass "Java" "$java_full"
      else
        fail "Java" "version $java_major found, need ≥ 25  →  https://adoptium.net"
      fi
    else
      fail "Java" "not found — install JDK 25  →  https://adoptium.net"
    fi

    # Docker with Compose v2 plugin
    if command -v docker >/dev/null 2>&1; then
      if docker compose version >/dev/null 2>&1; then
        compose_ver=$(docker compose version --short 2>/dev/null || docker compose version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
        pass "Docker" "$(docker --version)  +  Compose ${compose_ver}"
      else
        fail "Docker" "Compose plugin missing — enable in Docker Desktop or see https://docs.docker.com/compose/install/"
      fi
    else
      fail "Docker" "not found — install Docker Desktop  →  https://docs.docker.com/get-started/get-docker/"
    fi

    # just 1.46+ ([arg] attribute used in this Justfile)
    if command -v just >/dev/null 2>&1; then
      just_ver=$(just --version | awk '{print $2}')
      if version_gte "$just_ver" "1.46.0"; then
        pass "just" "$just_ver"
      else
        fail "just" "version $just_ver found, need ≥ 1.46  →  https://github.com/casey/just#packages"
      fi
    else
      fail "just" "not found  →  https://github.com/casey/just#packages"
    fi

    echo ""

    # ── Optional (needed for specific recipe groups) ───────────────────────────

    # hurl 7+ (api-test* recipes)
    if command -v hurl >/dev/null 2>&1; then
      hurl_ver=$(hurl --version | awk 'NR==1{print $2}')
      if version_gte "$hurl_ver" "7.0.0"; then
        pass "hurl" "$hurl_ver  (api-test*)"
      else
        warn "hurl" "version $hurl_ver found, need ≥ 7.0 for api-test*  →  https://hurl.dev/docs/installation.html"
      fi
    else
      warn "hurl" "not found — needed for api-test* recipes  →  https://hurl.dev/docs/installation.html"
    fi

    # Node.js 22+ (ui-* and openapi recipes)
    if command -v node >/dev/null 2>&1; then
      node_ver=$(node --version | tr -d 'v')
      if version_gte "$node_ver" "22.0.0"; then
        pass "Node.js" "v${node_ver}  (ui-*, openapi)"
      else
        warn "Node.js" "version $node_ver found, need ≥ 22 for ui-*/openapi  →  https://nodejs.org"
      fi
    else
      warn "Node.js" "not found — needed for ui-* and openapi recipes  →  https://nodejs.org"
    fi

    echo ""
    echo "────────────────────────────────────────"
    if [ "$failed" = "true" ]; then
      echo "  Required tools are missing. See README.MD § Prerequisites for setup."
      echo ""
      exit 1
    else
      printf '  \033[32mAll required tools are present.\033[0m\n'
      echo ""
    fi

# Start the full stack (Kvasir + all backing services)
[group('compose')]
compose-up:
    docker compose -f compose/compose.yml up -d

# Stop the full stack — pass --wipe to also remove volumes and orphans
[group('compose')]
[arg("wipe", long="wipe", value="true")]
compose-down wipe="false":
    docker compose -f compose/compose.yml down \
      {{ if wipe == "true" { "--volumes --remove-orphans" } else { "" } }}

# Start backing services only on devservices ports (28xxx) — for use alongside `just dev` or full stack
[group('compose')]
dev-services-up:
    docker compose -f compose/compose.devservices.yml \
      --env-file compose/.env.devservices up -d

# Stop backing services — pass --wipe to also remove volumes and orphans
[group('compose')]
[arg("wipe", long="wipe", value="true")]
dev-services-down wipe="false":
    docker compose -f compose/compose.devservices.yml \
      --env-file compose/.env.devservices down \
      {{ if wipe == "true" { "--volumes --remove-orphans" } else { "" } }}

# ── Development ───────────────────────────────────────────────────────────────

# Run Kvasir in dev mode (backing services managed by Maven)
[group('dev')]
dev:
    ./mvnw -T 0.5C compile quarkus:dev

# Run in dev mode without UI build
[group('dev')]
dev-no-ui:
    ./mvnw -T 0.5C compile quarkus:dev -Dui-service.phase=none

# Stop dev backing services and clean build output
[group('dev')]
clean:
    ./mvnw -T 0.5C clean

# ── Build ─────────────────────────────────────────────────────────────────────

# Full build (compile + test + package + install all modules)
[group('build')]
build:
    ./mvnw install

# Build services only, skipping tests
[group('build')]
build-fast:
    ./mvnw -T 0.5C package -Pservices -DskipTests -Dcompose.skip=true -Dmodel-tx.skip=true

# ── Tests ─────────────────────────────────────────────────────────────────────

# Run all unit tests (Maven manages backing services automatically)
[group('test')]
test:
    ./mvnw test -Pservices -Dmodel-tx.skip=true

# Test a single service and its dependencies: just test-service query-api
[group('test')]
test-service service:
  ./mvnw test -Pservices -pl kvasir-services:{{service}} -am -Dmodel-tx.skip=true

# Run a single test class: just test-class QueryApiTest
[group('test')]
test-class class:
    ./mvnw test -Pservices -Dtest={{class}} -Dmodel-tx.skip=true -Dsurefire.failIfNoSpecifiedTests=false

# Run a single test method: just test-method QueryApiTest#testGetPerson
[group('test')]
test-method method:
    ./mvnw test -Pservices -Dtest={{method}} -Dmodel-tx.skip=true -Dsurefire.failIfNoSpecifiedTests=false

# ── API Tests (hurl) ──────────────────────────────────────────────────────────

# Run API tests against local full compose stack (copy api-tests/hurl.env.example → api-tests/hurl.env first)
# Pass --report to generate an HTML report; use -- to forward hurl flags: just api-test -- --verbose
[group('api-tests')]
[arg("report", long="report", value="true")]
api-test report="false" *args:
    hurl --variables-file api-tests/hurl.env \
         --variable ts={{ts}} \
         --test \
         {{ if report == "true" { "--report-html api-tests/report" } else { "" } }} \
         api-tests/ {{args}}

# Run API tests against local dev setup (just dev-services-up + ./mvnw compile quarkus:dev)
# Pass --report to generate an HTML report; use -- to forward hurl flags: just api-test-dev -- --verbose
[group('api-tests')]
[arg("report", long="report", value="true")]
api-test-dev report="false" *args:
    hurl --variables-file api-tests/hurl.env.devservices \
         --variable ts={{ts}} \
         --test \
         {{ if report == "true" { "--report-html api-tests/report" } else { "" } }} \
         api-tests/ {{args}}

# Serve the latest hurl HTML report at http://localhost:8765
[group('api-tests')]
serve-api-report:
    npx --yes http-server "api-tests/report/" -p 8765 -o

# ── Docs ──────────────────────────────────────────────────────────────────────

# Build and serve OpenAPI docs locally (requires monolith to be packaged first: just build-fast)
[group('docs')]
openapi:
    npx --yes @redocly/cli build-docs \
      -o ./target/_openapi/index.html \
      ./services/monolith/target/openapi/openapi.yaml \
      --theme.openapi.hideHostname=true \
      --theme.openapi.pathInMiddlePanel=true
    npx --yes http-server ./target/_openapi -p 8765 -o


# ── Release ───────────────────────────────────────────────────────────────────

# Dry-run the Releasaurus release-pr locally.
# Uses --forge local so no remote forge is contacted and no PR is created.
# Releasaurus requires the URL path to match the local filesystem path, so we
# construct the repo URL from justfile_directory() rather than hardcoding it.
[group('release')]
release-dry-run:
    releasaurus release-pr \
        --forge local \
        --repo "https://localhost{{justfile_directory()}}" \
        --base-branch main \
        --dry-run
