package templates

// Service catalog — metadata for each Kvasir microservice.
// Used by templates to generate Deployments, Services, and IngressRoutes.
//
// Adding a new service:
//   1. Add entry here with kind, port, image suffix, routes (if HTTP)
//   2. The service will automatically be available in values.services.<name>

#ServiceKind: "http" | "worker" | "job"

#RouteMatch: {
	// Path pattern for matching (regex or prefix)
	pattern: string
	// Match type: "regex" for PathRegexp, "prefix" for PathPrefix, "exact" for Path
	matchType: *"regex" | "prefix" | "exact"
	// Traefik route priority. 0 = use default rule-length sorting. Negative values supported.
	priority: int | *0
}

#ServiceMeta: {
	// Service kind: http (needs Service + optional Ingress), worker (Deployment only), job (Job)
	kind: #ServiceKind
	// Container port
	port: *8080 | int
	// Management port for health checks
	managementPort: *9100 | int
	// Image name suffix (appended to registry base)
	imageSuffix: string
	// Route patterns for ingress (only for kind: "http")
	routes: [...#RouteMatch]
	// Whether this service needs a Kubernetes Service resource
	needsService: *true | bool
}

// The service catalog — single source of truth for all Kvasir services
#ServiceCatalog: {
	// ─────────────────────────────────────────────────────────────────────────────
	// HTTP Services (externally accessible)
	// ─────────────────────────────────────────────────────────────────────────────

	// ui-service: Web UI (Angular SPA)
	"ui-service": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "ui-service"
		routes: [{
			// Static frontend assets and SPA
			pattern:   "/_ui"
			matchType: "prefix"
		}]
	}

	// solid-api: Solid Protocol (LDP) implementation
	"solid-api": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "solid-api"
		routes: [{
			// Solid/LDP resources under /{podId}/solid/
			pattern:   "^/[^/]+/solid/"
			matchType: "regex"
		}]
	}

	// storage-api: S3 proxy for blob storage
	"storage-api": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "storage-api"
		routes: [
			{
				// Slice-scoped S3 operations
				pattern:   "^/[^/]+/slices/[^/]+/s3/"
				matchType: "regex"
			},
			{
				// Pod-scoped S3 operations
				pattern:   "^/[^/]+/s3/"
				matchType: "regex"
			},
		]
	}

	// kg-stream-api: SSE event streaming (changes, queries, lifecycle, S3)
	"kg-stream-api": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "kg-stream-api"
		routes: [{
			// Event streams: /events/changes, /events/query, /events/life-cycle, /events/s3
			pattern:   "^/[^/]+/events/"
			matchType: "regex"
		}]
	}

	// kg-query-api: GraphQL query endpoint for Knowledge Graph
	"kg-query-api": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "kg-query-api"
		routes: [
			{
				// Tagged slice queries
				pattern:   "^/[^/]+/slices/[^/]+/tags/[^/]+/query$"
				matchType: "regex"
			},
			{
				// Slice queries
				pattern:   "^/[^/]+/slices/[^/]+/query$"
				matchType: "regex"
			},
			{
				// Pod-level queries
				pattern:   "^/[^/]+/query$"
				matchType: "regex"
			},
		]
	}

	// kg-changes-api: Changes/mutations inbox for Knowledge Graph
	"kg-changes-api": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "kg-changes-api"
		routes: [
			{
				// Tagged slice changes
				pattern:   "^/[^/]+/slices/[^/]+/tags/[^/]+/changes"
				matchType: "regex"
			},
			{
				// Slice changes
				pattern:   "^/[^/]+/slices/[^/]+/changes"
				matchType: "regex"
			},
			{
				// Pod-level changes
				pattern:   "^/[^/]+/changes"
				matchType: "regex"
			},
		]
	}

	// pod-management-api: Pod and slice lifecycle management
	"pod-management-api": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "pod-management-api"
		routes: [
			{
				// Slice tag aliases
				pattern:   "^/[^/]+/slices/[^/]+/tags/[^/]+/alias/"
				matchType: "regex"
			},
			{
				// Slice tags
				pattern:   "^/[^/]+/slices/[^/]+/tags"
				matchType: "regex"
			},
			{
				// Individual slices
				pattern:   "^/[^/]+/slices/[^/]+$"
				matchType: "regex"
			},
			{
				// Slices list
				pattern:   "^/[^/]+/slices$"
				matchType: "regex"
			},
			{
				// ReBAC relationships
				pattern:   "^/[^/]+/rebac/"
				matchType: "regex"
			},
			{
				// Pod config endpoints
				pattern:   "^/[^/]+/(runtime-config|platform-config)$"
				matchType: "regex"
			},
			{
				// Individual pod
				pattern:   "^/[^/_][^/]*$"
				matchType: "regex"
			},
			{
				// Root (pod list/register)
				pattern:   "/"
				matchType: "exact"
			},
		]
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// Backend Services (no external HTTP)
	// ─────────────────────────────────────────────────────────────────────────────

	// kg-change-processor: Kafka consumer that processes change requests
	"kg-change-processor": #ServiceMeta & {
		kind:         "worker"
		imageSuffix:  "kg-change-processor"
		needsService: false
		routes: []
	}

	// simple-rdf-ingester: Kafka consumer that ingests RDF from S3 uploads
	"simple-rdf-ingester": #ServiceMeta & {
		kind:         "worker"
		imageSuffix:  "simple-rdf-ingester"
		needsService: false
		routes: []
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// Bootstrap Service (runs as Job)
	// ─────────────────────────────────────────────────────────────────────────────

	// init-service: Bootstrap job (Keycloak realm, OpenFGA model, pod setup)
	"init-service": #ServiceMeta & {
		kind:         "job"
		imageSuffix:  "init-service"
		needsService: false
		routes: []
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// Monolith (all services in one container)
	// ─────────────────────────────────────────────────────────────────────────────

	// monolith: All services bundled in single container
	"monolith": #ServiceMeta & {
		kind:        "http"
		imageSuffix: "monolith"
		// Monolith handles ALL routes — single catch-all
		routes: [{
			pattern:   "/"
			matchType: "prefix"
		}]
	}
}

// List of HTTP services (for ingress generation)
#HTTPServices: [
	for name, meta in #ServiceCatalog
	if meta.kind == "http" {name},
]

// List of worker services (Kafka consumers)
#WorkerServices: [
	for name, meta in #ServiceCatalog
	if meta.kind == "worker" {name},
]

// List of all runtime services (excludes init-service job)
#RuntimeServices: [
	for name, meta in #ServiceCatalog
	if meta.kind != "job" {name},
]
