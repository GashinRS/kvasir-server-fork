# Kvasir Server – common development tasks
#
# Run `just prereqs` first to verify all required tools are installed.
# Run `just` or `just --list` to see all recipes.
#
#  Global variables ───────────────────────────────────────────────────────────────
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

    # timoni 0.22+ (timoni-* recipes)
    if command -v timoni >/dev/null 2>&1; then
      timoni_ver=$(timoni version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
      if version_gte "$timoni_ver" "0.22.0"; then
        pass "timoni" "$timoni_ver  (timoni-*)"
      else
        warn "timoni" "version $timoni_ver found, need ≥ 0.22 for timoni-*  →  https://timoni.sh/install/"
      fi
    else
      warn "timoni" "not found — needed for timoni-* recipes  →  https://timoni.sh/install/"
    fi

    # cosign (optional, for signed module distribution)
    if command -v cosign >/dev/null 2>&1; then
      cosign_ver=$(cosign version 2>&1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
      pass "cosign" "${cosign_ver:-unknown}  (timoni signing)"
    else
      warn "cosign" "not found — needed for signed timoni module distribution  →  https://docs.sigstore.dev/cosign/system_config/installation/"
    fi

    # kind (kind-* recipes)
    if command -v kind >/dev/null 2>&1; then
      kind_ver=$(kind version | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
      pass "kind" "${kind_ver:-unknown}  (kind-*)"
    else
      warn "kind" "not found — needed for kind-* recipes  →  https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
    fi

    # kubectl 1.28+ (kind-* recipes)
    if command -v kubectl >/dev/null 2>&1; then
      kubectl_ver=$(kubectl version --client -o json 2>/dev/null | grep -oE '"gitVersion": "v[0-9]+\.[0-9]+\.[0-9]+"' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
      pass "kubectl" "${kubectl_ver:-unknown}  (kind-*)"
    else
      warn "kubectl" "not found — needed for kind-* recipes  →  https://kubernetes.io/docs/tasks/tools/"
    fi

    # helm 3+ (kind-* Traefik install)
    if command -v helm >/dev/null 2>&1; then
      helm_ver=$(helm version --short 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
      pass "helm" "${helm_ver:-unknown}  (kind-* Traefik)"
    else
      warn "helm" "not found — needed for Traefik install in kind-*  →  https://helm.sh/docs/intro/install/"
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

# Run API tests. Env: compose (default), dev, kind, or ci.
# Pass --report to generate an HTML report; use -- to forward hurl flags: just api-test -- --verbose
# For kind in CI, set KIND_RESOLVE_ARGS="--resolve kvasir.localhost:80:<ip> --resolve keycloak.localhost:80:<ip>".
[group('api-tests')]
[doc("Run API tests with hurl. Env: compose (default), dev, kind, or ci. Pass --report to generate an HTML report.")]
[arg("report", long="report", value="true")]
api-test env="compose" report="false" *args:
    #!/usr/bin/env sh
    set -e
    case "{{env}}" in
      compose) ENV_FILE="api-tests/hurl.env" ;;
      dev)     ENV_FILE="api-tests/hurl.env.devservices" ;;
      kind)    ENV_FILE="api-tests/hurl.env.kind" ;;
      ci)      ENV_FILE="api-tests/hurl.env.ci" ;;
      *) echo "Unknown env: {{env}}. Use compose, dev, kind, or ci."; exit 1 ;;
    esac
    REPORT_ARGS=""
    if [ "{{report}}" = "true" ]; then
      REPORT_ARGS="--report-html api-tests/report"
    fi
    # CI envs always produce JUnit for artifact collection
    case "{{env}}" in
      kind) REPORT_ARGS="$REPORT_ARGS --report-junit api-tests/junit-kind.xml" ;;
      ci)   REPORT_ARGS="$REPORT_ARGS --report-junit api-tests/junit-compose.xml" ;;
    esac
    hurl --variables-file "$ENV_FILE" \
         --variable ts={{ts}} \
         --test \
         $REPORT_ARGS \
         ${KIND_RESOLVE_ARGS:-} \
         {{args}} \
         api-tests/

# Serve the latest hurl HTML report at http://localhost:8765
[group('api-tests')]
serve-api-report:
    npx --yes http-server "api-tests/report/" -p 8765 -o

# ── Docs ──────────────────────────────────────────────────────────────────────

# Build OpenAPI docs (internal helper)
[private]
_openapi-build:
    npx --yes @redocly/cli build-docs \
      -o ./target/_openapi/index.html \
      ./services/monolith/target/openapi/openapi.yaml \
      --theme.openapi.hideHostname=true \
      --theme.openapi.pathInMiddlePanel=true

# Build and serve OpenAPI docs locally (requires monolith to be packaged first: just build-fast)
[group('docs')]
openapi:
    just _openapi-build
    npx --yes http-server ./target/_openapi -p 8765 -o

# Build and watch OpenAPI docs with live reload
[group('docs')]
openapi-watch:
    just _openapi-build && \
    npx --yes browser-sync start \
      --server ./target/_openapi \
      --port 8765 \
      --files "./target/_openapi/index.html" \
      --no-notify & \
    while inotifywait -e close_write ./services/monolith/target/openapi/openapi.yaml; do \
      just _openapi-build; \
    done


# Writerside builder image — kept in sync with WRITERSIDE_IMAGE in .gitlab-ci.yml
writerside_image := "gitlab.ilabt.imec.be:4567/discover/ci-tools/writerside-builder:2025.04.8412"
writerside_instance := "Writerside/kd"
writerside_artifact := "webHelpKD2-all.zip"

# Build the Writerside docs into ./public (mirrors the docs:writerside CI job)
[group('docs')]
docs-build:
    #!/usr/bin/env bash
    set -euo pipefail
    mkdir -p public
    docker run --rm \
      -u "$(id -u):$(id -g)" \
      -v "{{justfile_directory()}}":/srv \
      -w /srv \
      -e HOME=/tmp \
      --entrypoint /bin/bash \
      {{writerside_image}} \
      -c '
        set -eu
        export DISPLAY=:99
        Xvfb :99 &
        XVFB_PID=$!
        trap "kill $XVFB_PID 2>/dev/null || true" EXIT
        if ! command -v d2 >/dev/null 2>&1; then
          curl -fsSL https://d2lang.com/install.sh | sh -s -- -d /tmp
          export PATH=/tmp:$PATH
        fi
        /opt/builder/bin/idea.sh helpbuilderinspect \
          -source-dir . \
          -product {{writerside_instance}} \
          --runner other \
          -output-dir public/ || true
        test -f public/{{writerside_artifact}}
      '
    echo "✓ Built public/{{writerside_artifact}}"

[group('docs')]
_dev_rebuild:
  #!/usr/bin/env bash
  just docs-build
  rm -rf public/site
  unzip -q -o "public/{{writerside_artifact}}" -d public/site
  echo "✓ Rebuilt docs at $(date +%H:%M:%S)"


# Build, unzip, and serve docs at http://localhost:8765, rebuilding on change (requires watchexec via `mise install`)
[group('docs')]
docs-dev:
    #!/usr/bin/env bash
    set -euo pipefail

    if ! command -v watchexec >/dev/null 2>&1; then
      echo "✗ watchexec not found — run 'mise install' (see mise.toml)" >&2
      exit 1
    fi

    npx --yes http-server public/site -p 8765 -c-1 -s &
    SERVER_PID=$!
    trap "kill $SERVER_PID 2>/dev/null || true" EXIT

    echo ""
    echo "📚 Docs serving at http://localhost:8765"
    echo "👀 Watching Writerside/ — refresh browser after each rebuild"
    echo ""

    watchexec \
      --watch Writerside \
      --exts md,xml,d2,tree,list,cfg,svg,png \
      --debounce 500ms \
      --restart \
      -- just _dev_rebuild

# Remove built docs artifacts (uses Docker to handle root-owned files from older builds)
[group('docs')]
docs-clean:
    #!/usr/bin/env bash
    if [ -d public ]; then
      rm -rf public 2>/dev/null || \
        docker run --rm -v "{{justfile_directory()}}":/srv -w /srv alpine rm -rf public
    fi
    echo "✓ Cleaned public/"

# ── Release ───────────────────────────────────────────────────────────────────

# Dry-run the Releasaurus release-pr locally.
# Uses --forge local so no remote forge is contacted and no PR is created.
# Releasaurus requires the URL path to match the local filesystem path, so we
# construct the repo URL from justfile_directory() rather than hardcoding it.
[doc("Dry-run the Releasaurus release-pr locally.")]
[group('release')]
release-dry-run:
    releasaurus release-pr \
        --forge local \
        --repo "https://localhost{{justfile_directory()}}" \
        --base-branch main \
        --dry-run

# ── Timoni ────────────────────────────────────────────────────────────────────

timoni_registry := "harbor.discover.ilabt.imec.be"
timoni_module   := "oci://" + timoni_registry + "/library/kvasir"

# Validate the Timoni module CUE schema
[group('timoni')]
timoni-lint:
    timoni mod vet timoni/kvasir

# Push the Timoni module to the OCI registry (version from pom.xml)
[group('timoni')]
timoni-push:
    #!/usr/bin/env sh
    set -eu
    VERSION=$(grep -oP '(?<=<revision>)[^<]+' pom.xml)
    echo "Pushing timoni module v${VERSION} to {{timoni_module}}..."
    timoni mod push timoni/kvasir \
      {{timoni_module}} \
      --version="${VERSION}" \
      --latest=true \
      -a "org.opencontainers.image.source=$(git remote get-url origin)" \
      -a "org.opencontainers.image.description=Timoni module for deploying Kvasir to Kubernetes" \
      -a "org.opencontainers.image.vendor=imec"

# List published Timoni module versions
[group('timoni')]
timoni-list:
    timoni mod list {{timoni_module}}

# Pull and inspect a module version: just timoni-pull 0.18.3
[group('timoni')]
timoni-pull version:
    timoni mod pull {{timoni_module}}:{{version}} --output /tmp/kvasir-module
    ls -la /tmp/kvasir-module/

# ── Kind (local Kubernetes quickstart) ──────────────────────────────────────

# Create Kind cluster, deploy all dependencies and Kvasir, wait for ready.
# Mode: mono[lith] (default) or ms/micro[services].
[doc("Create Kind cluster, deploy all dependencies and Kvasir, wait for ready. Mode: mono[lith] (default) or ms/micro[services].")]
[group('kind')]
kind-up mode="mono":
    #!/usr/bin/env sh
    set -e
    echo "==> Creating Kind cluster..."
    kind create cluster --config kind/cluster-config.yaml --name kvasir --wait 60s
    kubectl config use-context kind-kvasir
    just kind-deploy-deps
    just kind-deploy-traefik
    just kind-deploy-kvasir {{mode}}
    echo ""
    echo "Kvasir is running!"
    echo "  Kvasir:     http://kvasir.localhost"
    echo "  Keycloak:  http://keycloak.localhost"
    echo ""

# Delete the Kind cluster
[group('kind')]
kind-down:
    kind delete cluster --name kvasir

# Deploy only dependency manifests (does not recreate cluster)
[group('kind')]
kind-deploy-deps:
    #!/usr/bin/env sh
    set -e
    kubectl apply -f kind/manifests/namespaces.yaml
    kubectl apply -f kind/manifests/postgres/postgres.yaml
    kubectl rollout status deployment/postgresql -n openfga --timeout=120s
    kubectl apply -f kind/manifests/clickhouse/clickhouse.yaml
    kubectl apply -f kind/manifests/kafka/kafka.yaml
    kubectl apply -f kind/manifests/seaweedfs/seaweedfs.yaml
    kubectl apply -f kind/manifests/openfga/openfga.yaml
    kubectl apply -f kind/manifests/keycloak/keycloak.yaml
    kubectl rollout status deployment/clickhouse -n clickhouse --timeout=120s
    kubectl rollout status deployment/kafka -n kafka --timeout=120s
    kubectl rollout status deployment/seaweedfs -n seaweedfs --timeout=120s
    kubectl rollout status deployment/openfga -n openfga --timeout=120s
    kubectl rollout status deployment/keycloak -n keycloak --timeout=180s

# Install Traefik via Helm, apply IngressRoutes, and patch CoreDNS for *.localhost resolution.
[group('kind')]
kind-deploy-traefik:
    #!/usr/bin/env sh
    set -e
    helm repo add traefik https://traefik.github.io/charts --force-update
    helm repo update
    helm upgrade --install traefik traefik/traefik \
      --namespace traefik --create-namespace \
      -f kind/manifests/traefik/traefik-values.yaml \
      --wait --timeout 120s
    kubectl apply -f kind/manifests/traefik/ingress-routes.yaml
    kubectl apply -f kind/manifests/coredns-patch.yaml
    kubectl rollout restart deployment/coredns -n kube-system
    kubectl rollout status deployment/coredns -n kube-system --timeout=60s

# Deploy Kvasir via Timoni (cluster and deps must already be running).
# Mode: mono[lith] (default) or ms/micro[services].
# Override image with KVASIR_IMAGE / KVASIR_TAG / KVASIR_PULL_POLICY env vars.
[doc("Deploy Kvasir via Timoni (cluster and deps must already be running). Mode: mono[lith] (default) or ms/micro[services]. Override image with KVASIR_IMAGE / KVASIR_TAG / KVASIR_PULL_POLICY env vars.")]
[group('kind')]
kind-deploy-kvasir mode="mono":
    #!/usr/bin/env sh
    set -e
    # Normalize mode abbreviations
    case "{{mode}}" in
      mono|monolith|m)       MODE="monolith" ;;
      ms|micro|microservices) MODE="microservices" ;;
      *) echo "Unknown mode: {{mode}}. Use mono[lith] or ms/micro[services]."; exit 1 ;;
    esac

    VALUES_STRING="--values kind/values-kind-base.cue"
    # Select values file based on mode
    if [ "$MODE" = "microservices" ]; then
      VALUES_STRING="$VALUES_STRING --values kind/values-kind-microservices.cue"
    else
      VALUES_STRING="$VALUES_STRING --values kind/values-kind-monolith.cue"
    fi

    # Build image override file if env vars are set
    OVERRIDE_FILE=""
    if [ -n "${KVASIR_IMAGE:-}" ] || [ -n "${KVASIR_TAG:-}" ] || [ -n "${KVASIR_PULL_POLICY:-}" ]; then
      OVERRIDE_FILE="$(mktemp /tmp/kind-image-override.XXXXXX.cue)"
      printf 'values: image: {\n' > "$OVERRIDE_FILE"
      [ -n "${KVASIR_IMAGE:-}" ]       && printf '  repository: "%s"\n' "${KVASIR_IMAGE}"       >> "$OVERRIDE_FILE"
      [ -n "${KVASIR_TAG:-}" ]         && printf '  tag: "%s"\n'        "${KVASIR_TAG}"         >> "$OVERRIDE_FILE"
      [ -n "${KVASIR_PULL_POLICY:-}" ] && printf '  pullPolicy: "%s"\n' "${KVASIR_PULL_POLICY}" >> "$OVERRIDE_FILE"
      printf '}\n' >> "$OVERRIDE_FILE"
      VALUES_STRING="$VALUES_STRING --values $OVERRIDE_FILE"
    fi

    echo "Applying with values $VALUES_STRING"
    # Apply Timoni module
    timoni apply -n kvasir kvasir timoni/kvasir $VALUES_STRING

    # Wait for init job (microservices only)
    if [ "$MODE" = "microservices" ]; then
      kubectl wait --for=condition=Complete job/kvasir-init -n kvasir --timeout=180s || true
    fi

    # Wait for all deployments
    for deploy in $(kubectl get deploy -n kvasir -o name); do
      kubectl rollout status "$deploy" -n kvasir --timeout=180s
    done

# Alias for `api-test kind` (backwards compatibility)
[group('kind')]
kind-test *args:
    just api-test kind {{args}}

# Load a locally built Docker image into the Kind cluster: just kind-load-image my-image:tag
[group('kind')]
kind-load-image image:
    kind load docker-image {{image}} --name kvasir

# ── Time-series Benchmarks (Kvasir KG) ──────────────────────────────────────

# Load time-series sensor data into Kvasir (configure via benchmarks/.env)
[group('benchmarks')]
bench-timeseries-load *args:
    cd benchmarks && python load_data.py

# Run time-series query benchmarks against Kvasir (configure via benchmarks/.env)
[group('benchmarks')]
bench-timeseries-run *args:
    cd benchmarks && python run_benchmarks.py

# ── Comparative Benchmarks (Kvasir vs CSS) ──────────────────────────────────

# Start the CSS server for comparative benchmarks
[group('benchmarks')]
bench-compare-css-up:
    docker compose -f benchmarks/comparative/docker-compose.css.yml up -d

# Stop the CSS server — pass --wipe to also remove volumes (deletes all CSS pod data)
[group('benchmarks')]
[arg("wipe", long="wipe", value="true")]
bench-compare-css-down wipe="false":
    docker compose -f benchmarks/comparative/docker-compose.css.yml down \
      {{ if wipe == "true" { "--volumes --remove-orphans" } else { "" } }}

# Install Python dependencies for comparative benchmarks
[group('benchmarks')]
bench-compare-install:
    pip install -r benchmarks/comparative/requirements.txt

# Run comparative benchmarks. Use --scenario 1|2 to select scenario, --platform kvasir|kvasir-candidate|css|s3 to select platform(s).
[group('benchmarks')]
bench-compare *args:
    cd benchmarks/comparative && python run_all.py {{args}}

# Merge multiple CSV files into a single HTML report — pass --csv and optional --output to report.py.
# If called without arguments, defaults to results/results.csv → results/report.html.
#
# Examples:
#   just bench-compare-merge                         # default (results/results.csv)
#   just bench-compare-merge --csv results/kvasir.csv results/css.csv
#   just bench-compare-merge --csv results/kvasir.csv results/css.csv --output results/merged.html
[group('benchmarks')]
[doc('Generate the HTML report from results/results.csv. Use --csv to specify multiple CSVs, and --output to specify output file.')]
bench-compare-report *args:
    cd benchmarks/comparative && python report.py {{args}}

# Serve the benchmark report at http://localhost:8766
[group('benchmarks')]
bench-compare-serve:
    npx --yes http-server benchmarks/comparative/results -p 8766 -o

