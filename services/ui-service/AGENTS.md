# services/ui-service — Angular Frontend + Quarkus Serving

Embeds an Angular 21 frontend inside a Quarkus module. Maven builds the frontend
via `frontend-maven-plugin`; Quarkus serves the built SPA at `/_ui/`.

---

## Structure

```
src/main/frontend/               # Angular workspace (pnpm)
├── angular.json                  # Build config: outputPath → ../resources/webroot/_ui
├── package.json                  # Scripts: ng serve/build/test; preinstall enforces pnpm
├── pnpm-lock.yaml
├── src/
│   ├── main.ts                   # Bootstrap: fetches /_ui/_cfg/config.json before init
│   ├── app/
│   │   ├── app.routes.ts         # Route definitions (lazy-loaded, guards, resolvers)
│   │   ├── keycloak.config.ts    # Keycloak bearer token interceptor
│   │   └── services/
│   │       ├── config.service.ts # Runtime config (KVASIR_HOST, AUTH_HOST, AUTH_REALM)
│   │       ├── kvasir.service.ts # HTTP client for pod/slice/change/query APIs
│   │       └── rebac.service.ts  # ReBAC permission API client
│   └── environments/
src/main/kotlin/kvasir/services/ui/
└── SpaHandler.kt                 # Quarkus RouteFilter: serves SPA, config endpoint, fallback
src/main/resources/webroot/_ui/   # Built frontend output (committed)
```

---

## Build Pipeline

1. Maven `frontend-maven-plugin` (phase: `${ui-service.phase}`, default `generate-resources`):
   - Installs Node + pnpm into `target/`
   - Runs `pnpm install`
   - Runs `pnpm build` (Angular CLI `ng build`)
2. Angular outputs to `src/main/resources/webroot/_ui/` (committed to repo)
3. Quarkus packages `webroot/` into the JAR and serves it at `/_ui/`
4. Skip frontend build: `-Dui-service.phase=none` or `just dev-no-ui`

## Runtime Config Bridge

- `SpaHandler.kt` intercepts `/_ui/_cfg/config.json` and returns server-generated config:
  - `KVASIR_HOST` — backend API base URL
  - `KVASIR_AUTH_HOST` — Keycloak server URL
  - `KVASIR_AUTH_REALM` — Keycloak realm
- Frontend fetches this config in `main.ts` before Angular bootstrap.
- SPA fallback: any `/_ui/**` request not matching a file → `/_ui/index.html`.

## Frontend Dev

```bash
# Standalone Angular dev server (requires backend running separately)
cd services/ui-service/src/main/frontend
pnpm install
pnpm start    # ng serve
```

---

## Conventions

- State management: service + RxJS (no NgRx/global store).
- Auth: Keycloak token interceptor in `keycloak.config.ts`.
- Routes use lazy loading via `loadComponent(...)` with guards (`sessionActiveGuard`) and resolvers.
- No frontend↔backend shared type generation (TS types are manually maintained in `types.ts`).

---

## Gotchas

- Built frontend assets are committed to `src/main/resources/webroot/_ui/` — intentional design.
- pnpm version mismatch risk: `package.json` pins `10.29.2`, Maven plugin installs `10.30.3`.
- `SpaHandler` skips webroot scanning when config property `ui-service.phase` is `"none"`.
- Angular `.angular/cache/` may appear under `src/main/frontend/` — should be gitignored.
- `baseHref` is `/_ui/` — all Angular routes are relative to this prefix.
