# Kvasir API Endpoint Mapping

This document maps all HTTP endpoints to their responsible microservices for ingress configuration.

## Quick Reference

| Service              | Path Pattern         | Description           |
| -------------------- | -------------------- | --------------------- |
| `ui-service`         | `/_ui/**`            | Web UI (SPA)          |
| `solid-api`          | `/{podId}/solid/**`  | Solid Protocol (LDP)  |
| `storage-api`        | `/{podId}/s3/**`     | S3 blob storage proxy |
| `kg-stream-api`      | `/{podId}/events/**` | SSE event streams     |
| `kg-query-api`       | `**/query`           | GraphQL queries       |
| `kg-changes-api`     | `**/changes/**`      | KG mutations          |
| `pod-management-api` | Everything else      | Pod/slice management  |

---

## Service Overview

### HTTP Services (require Ingress)

| Service              | Port | Purpose                            |
| -------------------- | ---- | ---------------------------------- |
| `kg-query-api`       | 8080 | GraphQL queries on Knowledge Graph |
| `kg-changes-api`     | 8080 | KG mutations (changes inbox)       |
| `kg-stream-api`      | 8080 | SSE streaming of events            |
| `pod-management-api` | 8080 | Pod & slice CRUD                   |
| `solid-api`          | 8080 | Solid Protocol (LDP)               |
| `storage-api`        | 8080 | S3 proxy                           |
| `ui-service`         | 8080 | SPA frontend                       |

### Backend Services (no Ingress needed)

| Service               | Purpose                                   |
| --------------------- | ----------------------------------------- |
| `kg-change-processor` | Kafka consumer: processes change requests |
| `simple-rdf-ingester` | Kafka consumer: ingests RDF from S3       |
| `init-service`        | Bootstrap job (Keycloak, OpenFGA, pods)   |

---

## Routing Strategy

The routing is designed around **distinctive path segments** that make each service easy to identify:

```
Request Path                          → Service
─────────────────────────────────────────────────────────
/_ui/**                               → ui-service
/{podId}/solid/**                     → solid-api
/{podId}/s3/**                        → storage-api
/{podId}/slices/{sliceId}/s3/**       → storage-api
/{podId}/events/**                    → kg-stream-api
**/query                              → kg-query-api
**/changes/**                         → kg-changes-api
/ (root)                              → pod-management-api
/{podId}                              → pod-management-api
/{podId}/slices/**                    → pod-management-api
```

### Priority Rules

1. **Fixed prefixes first**: `/_ui` is unambiguous
2. **Unique segments next**: `/solid/`, `/s3/`, `/events/` are distinctive
3. **Suffix matching**: `/query` always terminates query paths
4. **Contains matching**: `/changes` always in changes paths
5. **Fallback last**: Pod management catches everything else

---

## Generic Ingress Patterns

### Path Matching Reference

| Service             | Regex Pattern                | Prefix Pattern | Priority |
| ------------------- | ---------------------------- | -------------- | -------- |
| ui-service          | N/A                          | `/_ui`         | 100      |
| solid-api           | `^/[^/]+/solid/`             | N/A            | 50       |
| storage-api (slice) | `^/[^/]+/slices/[^/]+/s3/`   | N/A            | 55       |
| storage-api (pod)   | `^/[^/]+/s3/`                | N/A            | 50       |
| kg-stream-api       | `^/[^/]+/events/`            | N/A            | 45       |
| kg-query-api        | `/query$` (suffix)           | N/A            | 25-35    |
| kg-changes-api      | `/changes` (contains)        | N/A            | 24-34    |
| pod-management-api  | `^/[^/]+$`, `^/[^/]+/slices` | `/` (fallback) | 1-22     |

### NGINX Ingress

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: kvasir
  annotations:
    nginx.ingress.kubernetes.io/use-regex: "true"
spec:
  rules:
    - host: kvasir.example.com
      http:
        paths:
          # UI - prefix match
          - path: /_ui
            pathType: Prefix
            backend:
              service:
                name: ui-service
                port:
                  number: 8080

          # Solid API - regex for /{podId}/solid/
          - path: /[^/]+/solid/
            pathType: ImplementationSpecific
            backend:
              service:
                name: solid-api
                port:
                  number: 8080

          # Storage API (slice-scoped) - higher priority
          - path: /[^/]+/slices/[^/]+/s3/
            pathType: ImplementationSpecific
            backend:
              service:
                name: storage-api
                port:
                  number: 8080

          # Storage API (pod-scoped)
          - path: /[^/]+/s3/
            pathType: ImplementationSpecific
            backend:
              service:
                name: storage-api
                port:
                  number: 8080

          # Stream API - regex for /{podId}/events/
          - path: /[^/]+/events/
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-stream-api
                port:
                  number: 8080

          # Query API - paths ending in /query
          - path: /[^/]+/slices/[^/]+/tags/[^/]+/query
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-query-api
                port:
                  number: 8080
          - path: /[^/]+/slices/[^/]+/query
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-query-api
                port:
                  number: 8080
          - path: /[^/]+/query
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-query-api
                port:
                  number: 8080

          # Changes API - paths containing /changes
          - path: /[^/]+/slices/[^/]+/tags/[^/]+/changes
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-changes-api
                port:
                  number: 8080
          - path: /[^/]+/slices/[^/]+/changes
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-changes-api
                port:
                  number: 8080
          - path: /[^/]+/changes
            pathType: ImplementationSpecific
            backend:
              service:
                name: kg-changes-api
                port:
                  number: 8080

          # Pod Management - fallback (must be last)
          - path: /
            pathType: Prefix
            backend:
              service:
                name: pod-management-api
                port:
                  number: 8080
```

### Traefik IngressRoute

```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: kvasir
spec:
  entryPoints:
    - websecure
  routes:
    # UI - highest priority, fixed prefix
    - match: PathPrefix(`/_ui`)
      kind: Rule
      priority: 100
      services:
        - name: ui-service
          port: 8080

    # Solid API
    - match: PathRegexp(`^/[^/]+/solid/`)
      kind: Rule
      priority: 50
      services:
        - name: solid-api
          port: 8080

    # Storage API (slice-scoped S3)
    - match: PathRegexp(`^/[^/]+/slices/[^/]+/s3/`)
      kind: Rule
      priority: 55
      services:
        - name: storage-api
          port: 8080

    # Storage API (pod-scoped S3)
    - match: PathRegexp(`^/[^/]+/s3/`)
      kind: Rule
      priority: 50
      services:
        - name: storage-api
          port: 8080

    # Stream API (SSE events)
    - match: PathRegexp(`^/[^/]+/events/`)
      kind: Rule
      priority: 45
      services:
        - name: kg-stream-api
          port: 8080

    # Query API - tagged slice queries
    - match: PathRegexp(`^/[^/]+/slices/[^/]+/tags/[^/]+/query$`)
      kind: Rule
      priority: 35
      services:
        - name: kg-query-api
          port: 8080

    # Query API - slice queries
    - match: PathRegexp(`^/[^/]+/slices/[^/]+/query$`)
      kind: Rule
      priority: 30
      services:
        - name: kg-query-api
          port: 8080

    # Query API - pod queries
    - match: PathRegexp(`^/[^/]+/query$`)
      kind: Rule
      priority: 25
      services:
        - name: kg-query-api
          port: 8080

    # Changes API - tagged slice changes
    - match: PathRegexp(`^/[^/]+/slices/[^/]+/tags/[^/]+/changes`)
      kind: Rule
      priority: 34
      services:
        - name: kg-changes-api
          port: 8080

    # Changes API - slice changes
    - match: PathRegexp(`^/[^/]+/slices/[^/]+/changes`)
      kind: Rule
      priority: 29
      services:
        - name: kg-changes-api
          port: 8080

    # Changes API - pod changes
    - match: PathRegexp(`^/[^/]+/changes`)
      kind: Rule
      priority: 24
      services:
        - name: kg-changes-api
          port: 8080

    # Pod Management - fallback (lowest priority)
    - match: PathPrefix(`/`)
      kind: Rule
      priority: 1
      services:
        - name: pod-management-api
          port: 8080
```

## Health Check Endpoints

All services expose health endpoints on the **management port (9100)**:

| Path              | Purpose         |
| ----------------- | --------------- |
| `/q/health/live`  | Liveness probe  |
| `/q/health/ready` | Readiness probe |
| `/q/health`       | Combined health |

Configure your ingress health checks to use port 9100, not 8080.

---

## Monolith vs Microservices

### Monolith Mode

All routes point to single `monolith` service:

```yaml
- path: /
  pathType: Prefix
  backend:
    service:
      name: kvasir-monolith
      port:
        number: 8080
```

### Microservices Mode

Use the routing rules documented above to route to individual services.

**Important**: Never enable both modes simultaneously — the monolith and individual services would conflict on the same endpoints.
