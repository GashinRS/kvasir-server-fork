package templates

import (
	"strings"
	"list"
)

// Kvasir application configuration schema.
// Based on Configuration-Reference.md.
// _name and _namespace are primitive string params injected from #Config.metadata.
// A full #config: #Config parameter cannot be used here because #ApplicationConfig
// is embedded in #Config itself — that would create a circular dependency.
// Additionally, yaml.Marshal (called in #ConfigMap.#Data) requires eager concreteness:
// built-in marshal functions evaluate at definition time, before #config bindings
// from #Instance are applied. Field references (like in #Deployment) are lazy and work fine.
//
// SENSITIVE FIELDS HAVE NO DEFAULTS. Fields backed by #SecretFieldRegistry
// (admin-client credentials, s3 access/secret keys, clickhouse user/password,
// http-endpoint-policy-enforcer credentials) are intentionally left unset so
// that an unconfigured deployment fails fast at vet/build time rather than
// silently shipping dev placeholders into the ConfigMap.
//
// For local/dev installs, overlay `dev-values.cue` (see module README) which
// provides safe-to-leak placeholders together with `secrets.<integration>.manage: true`
// — the placeholders are then materialized into a module-managed Secret instead
// of the ConfigMap.
#ApplicationConfig: {
	_name:      string
	_namespace: string

	// HTTP Configuration
	http: {
		"base-uri":      *"http://\(_name).\(_namespace).svc.cluster.local:8080/" | string
		"webclient-uri": *"http://\(_name).\(_namespace).svc.cluster.local:8080/_ui/" | string
	}

	// Pod Configuration - all optional; omitted keys fall back to application defaults.
	pod?: {
		"default-context"?: string
		"auto-ingest-rdf"?: bool

		auth?: {
			"enable-solid-web-id"?: bool
			"require-dpop"?:        bool
			"skip-dpop-ath-check"?: bool

			// OIDC Authentication - omit entirely to use application defaults.
			oidc?: {
				"server-url"?: string
				"principal-extractor"?: {
					"class-name"?: string
					config?:       string
				}
				"jwt-allowed-clock-skew-seconds"?: int
			}

			// UMA Authentication - omit entirely to use application defaults.
			uma?: {
				"server-url"?: string
				"principal-extractor"?: {
					"class-name"?: string
					config?:       string
				}
				"jwt-allowed-clock-skew-seconds"?: int
			}

			// HTTP Endpoint Policy Enforcer - omit entirely unless using a custom enforcer.
			"http-endpoint-policy-enforcer"?: {
				url?: string
				"basic-auth"?: {
					username?: string
					// SENSITIVE — see #SecretFieldRegistry. Inline values require
					// secrets.policyEnforcer.manage: true (or use existingSecret).
					password?: string
				}
				"api-key"?: {
					"key-name"?: string
					// SENSITIVE — see #SecretFieldRegistry. Inline values require
					// secrets.policyEnforcer.manage: true (or use existingSecret).
					"key-value"?: string
					"send-via"?:  "header" | "query"
				}
			}
		}
	}

	// Bootstrap Configuration - omit entirely if no bootstrap pods needed.
	bootstrap?: {
		pods?: [...{
			name:                                          string
			"owner-user-id"?:                              string
			"auto-register-uma"?:                          bool
			"auto-register-http-endpoint-policy-enforcer"?: bool
			"generate-clients"?: [...{
				"client-id":               string
				"enable-service-account"?: bool
				// DEV/TEST ONLY: `client-secret` is rendered inline into the ConfigMap
				// because list-shaped sensitive fields are not covered by the Secret
				// scrubbing/injection pipeline (which is map-keyed via
				// #SecretFieldRegistry). For production-like setups, omit this field
				// here and provision the OIDC client out-of-band, or mount an
				// external Secret/ConfigMap that overlays this entry. See the
				// "Bootstrap & dev/test secrets" section in the module README.
				"client-secret"?: string
				"redirect-uris"?: [...string]
				"enable-force-pkce"?: bool
				openfga?: relationships?: [...{
					"target-resource": string
					relations: [...string]
				}]
			}]
		}]
		"exit-after-setup"?: bool
	}

	// Knowledge Graph - Clickhouse Configuration
	// `user` and `password` are SENSITIVE — see #SecretFieldRegistry.
	kg: clickhouse: {
		host:      *"clickhouse.clickhouse.svc.cluster.local" | string
		port:      *8123 | int
		user?:     string
		password?: string
	}

	// Messaging - Kafka Configuration
	messaging: kafka: {
		"bootstrap-servers": *"kafka.kafka:9092" | string
	}

	// Storage - S3 Configuration
	// `access-key` and `secret-key` are SENSITIVE — see #SecretFieldRegistry.
	// Inline values require `secrets.s3.manage: true` (or use existingSecret).
	storage: s3: {
		endpoint:     *"http://seaweedfs-s3.seaweedfs:8333" | string
		region:       *"eu-west-1" | string
		"access-key"?: string
		"secret-key"?: string
	}

	// Authentication - Keycloak Configuration
	// All admin-client fields are SENSITIVE — see #SecretFieldRegistry.
	// Inline values require `secrets.keycloak.manage: true` (or use existingSecret).
	auth: keycloak: {
		url:   *"http://keycloak.keycloak:8280" | string
		realm: *"kvasir" | string
		"admin-client": {
			"grant-type":     *"password" | "client_credentials"
			username?:        string
			password?:        string
			"client-id"?:     string
			"client-secret"?: string
			"server-url":     *url | string
			"realm":          *"master" | string
		}
	}

	// Policy Enforcement - OpenFGA Configuration
	pep: openfga: {
		url: *"http://openfga.openfga:8380" | string
	}
}

// Quarkus framework configuration — marshaled as top-level `quarkus:` in application.yaml.
#QuarkusConfig: {
	log?: {
		level?: "FATAL" | "ERROR" | "WARN" | "INFO" | "DEBUG" | "TRACE"
		// Per-category log levels. Keys are Java package/class names.
		// Maps to quarkus.log.category."<name>".level in Quarkus property notation.
		category?: {
			[string]: {
				level?:       "FATAL" | "ERROR" | "WARN" | "INFO" | "DEBUG" | "TRACE" | "inherit"
				"min-level"?: "FATAL" | "ERROR" | "WARN" | "INFO" | "DEBUG" | "TRACE" | "inherit"
				handlers?: [...string]
				"use-parent-handlers"?: bool
			}
		}
	}
	tls?: [string]: #QuarkusTLSConfig
}

#QuarkusTLSConfig: {
	"trust-store": {
		pem: certs: string
	}
}

#KafkaTLS: {
	enabled:             *false | bool
	"config-name":       *"kafka" | string
	"security-protocol": *"SSL" | string
	"mount-path":        *"/home/jboss/tls/kafka" | string
	if enabled {
		"trust-secret": {
			name: string
			key:  *"ca.crt" | string
		}
	}
}

// Reference to a single key within a Kubernetes Secret.
#SecretKeyRef: {
	name: string & =~"^[a-z0-9-]+$"
	key:  string & !=""
}

// A single credential field inside an integration's secret binding.
//   - `key` is the key name inside the integration's `existingSecret` OR
//     module-managed Secret (used when no per-field `ref` is set).
//   - `ref` is an optional per-field override that points to a different
//     Secret (e.g. credentials shared with another component).
#SecretField: {
	key:  string & !=""
	ref?: #SecretKeyRef
}

// Per-integration secret binding. Each integration in `#Secrets` instantiates
// this with a fixed set of fields (see #SecretFieldRegistry).
//
// Resolution order per field (highest priority first):
//   1. `fields[<field>].ref` (per-field override → arbitrary Secret)
//   2. `existingSecret` + `fields[<field>].key` (integration-owned external Secret)
//   3. `manage: true` → module-managed Secret materializing inline values
//   4. Inline value in `applicationConfig` without any of the above → VALIDATION ERROR
//
// `manage` and `existingSecret` are mutually exclusive — combining them would
// emit a Secret that is shadowed by `existingSecret` for every non-`ref` field.
#SecretBinding: {
	// Name of an existing Secret that holds (some or all) credentials for
	// this integration. Leave unset to rely solely on per-field `ref`s,
	// `manage: true`, or have no sensitive values for this integration.
	existingSecret?: string & =~"^[a-z0-9-]+$"

	// When true, the module emits a Kubernetes Secret named
	// `<instanceName>-<integration>-secret` populated from inline values in
	// `applicationConfig` for this integration's registered fields. Mutually
	// exclusive with `existingSecret`. Requires at least one inline value to
	// be present for the integration's mapped fields.
	manage: *false | bool

	fields: [string]: #SecretField
}

// Sensitive credential sourcing, grouped per integration. Each integration
// owns its own (optional) Kubernetes Secret. Adding a new integration is a
// matter of:
//   1. declaring the binding here with default field keys, and
//   2. adding the corresponding `(integration, field) → property` rows
//      in #SecretFieldRegistry below.
//
// The ConfigMap, Deployment and managed-Secret templates derive scrubbing,
// env injection, and Secret emission entirely from these two structures —
// no per-field branching in the templates.
//
// Designed to integrate cleanly with External Secrets Operator / OpenBAO:
// those tools materialize the Secret(s); this module only references them by
// name + key (which can be customised per field if the upstream store dictates
// the key naming).
#Secrets: {
	keycloak: #SecretBinding & {
		fields: {
			adminUsername:     {key: *"admin-username" | string}
			adminPassword:     {key: *"admin-password" | string}
			adminClientId:     {key: *"admin-client-id" | string}
			adminClientSecret: {key: *"admin-client-secret" | string}
		}
	}
	s3: #SecretBinding & {
		fields: {
			accessKey: {key: *"access-key" | string}
			secretKey: {key: *"secret-key" | string}
		}
	}
	clickhouse: #SecretBinding & {
		fields: {
			user:     {key: *"user" | string}
			password: {key: *"password" | string}
		}
	}
	policyEnforcer: #SecretBinding & {
		fields: {
			basicAuthPassword: {key: *"basic-auth-password" | string}
			apiKeyValue:       {key: *"api-key-value" | string}
		}
	}
}

// Registry mapping each (integration, field) to:
//   - `property`: the dotted Quarkus/MicroProfile property path. Used by the
//     ConfigMap to scrub the field from `application.yaml` when secret-sourced
//     and by #InlineValueLookup to fish the value out of `applicationConfig`
//     for the module-managed Secret.
//   - `env` (OPTIONAL): explicit container environment variable name. When
//     unset, the env name is derived from `property` via #EnvName (standard
//     MicroProfile mapping: uppercase, `.` and `-` → `_`). Set this only for
//     edge cases where the standard mapping doesn't apply.
//
// Keep this in sync with #Secrets.<integration>.fields. Adding a field that
// is not in this registry is a vet-time error (templates iterate the registry).
#SecretFieldRegistry: {
	[string]: [string]: {
		property: string
		env?:     string
	}

	keycloak: {
		adminUsername:     {property: "kvasir.auth.keycloak.admin-client.username"}
		adminPassword:     {property: "kvasir.auth.keycloak.admin-client.password"}
		adminClientId:     {property: "kvasir.auth.keycloak.admin-client.client-id"}
		adminClientSecret: {property: "kvasir.auth.keycloak.admin-client.client-secret"}
	}
	s3: {
		accessKey: {property: "kvasir.storage.s3.access-key"}
		secretKey: {property: "kvasir.storage.s3.secret-key"}
	}
	clickhouse: {
		user:     {property: "kvasir.kg.clickhouse.user"}
		password: {property: "kvasir.kg.clickhouse.password"}
	}
	policyEnforcer: {
		basicAuthPassword: {property: "kvasir.pod.auth.http-endpoint-policy-enforcer.basic-auth.password"}
		apiKeyValue:       {property: "kvasir.pod.auth.http-endpoint-policy-enforcer.api-key.key-value"}
	}
}

// Derive the MicroProfile-mapped container env var name from a Quarkus
// property path: uppercase + replace `.` and `-` with `_`.
// See: https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html#default_configsources.env.mapping
//
// Edge cases (quoted dynamic keys like `quarkus.log.category."<name>".level`,
// list indices) are NOT supported — register an explicit `env` override in
// #SecretFieldRegistry for those.
#EnvName: {
	property: string
	out:      strings.ToUpper(strings.Replace(strings.Replace(property, ".", "_", -1), "-", "_", -1))
}

// Resolve the env var name for a registry entry: explicit `env` override
// when present, otherwise derived from `property`.
#ResolvedEnvName: {
	meta: {property: string, env?: string}
	out:  *meta.env | (#EnvName & {property: meta.property}).out
}

// Bounded struct lookup by string-list path. Returns `null` for missing
// segments (callers test with `out != null`). Bounded to 8 segments because
// recursive struct lookups in CUE either hit structural cycles or panic the
// evaluator with self-referential list comprehensions; the longest property
// path in #SecretFieldRegistry is 5 segments.
//
// Each step uses `*x[k] | null` so missing intermediate keys collapse to
// `null` instead of bottom (which would propagate and obscure validation).
//
// Used to fish inline values out of `applicationConfig` along the dotted
// property paths registered in #SecretFieldRegistry (the leading `kvasir`
// segment is dropped — applicationConfig IS the kvasir block).
#Lookup: {
	in: _
	path: [...string] & list.MaxItems(8)
	let _p = path
	let _n = len(_p)
	out: [
		if _n == 0 {in},
		if _n == 1 {*in[_p[0]] | null},
		if _n == 2 {*in[_p[0]][_p[1]] | null},
		if _n == 3 {*in[_p[0]][_p[1]][_p[2]] | null},
		if _n == 4 {*in[_p[0]][_p[1]][_p[2]][_p[3]] | null},
		if _n == 5 {*in[_p[0]][_p[1]][_p[2]][_p[3]][_p[4]] | null},
		if _n == 6 {*in[_p[0]][_p[1]][_p[2]][_p[3]][_p[4]][_p[5]] | null},
		if _n == 7 {*in[_p[0]][_p[1]][_p[2]][_p[3]][_p[4]][_p[5]][_p[6]] | null},
		if _n == 8 {*in[_p[0]][_p[1]][_p[2]][_p[3]][_p[4]][_p[5]][_p[6]][_p[7]] | null},
	][0]
}

// Module-managed Secret name for a given integration. Stable function of the
// instance name so users can reference it from external tooling if needed.
#ManagedSecretName: {
	instanceName: string
	integration:  string
	out:          "\(instanceName)-\(integration)-secret"
}
