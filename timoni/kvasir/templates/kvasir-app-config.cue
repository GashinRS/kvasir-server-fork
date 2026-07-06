package templates

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
			name:                                           string
			"owner-user-id"?:                               string
			"auto-register-uma"?:                           bool
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
		host: *"clickhouse.clickhouse.svc.cluster.local" | string
		port: *8123 | int
	}

	// Messaging - Kafka Configuration
	messaging: kafka: {
		"bootstrap-servers": *"kafka.kafka:9092" | string
	}

	// Storage - S3 Configuration
	// `access-key` and `secret-key` are SENSITIVE — see #SecretFieldRegistry.
	// Inline values require `secrets.s3.manage: true` (or use existingSecret).
	storage: s3: {
		endpoint: *"http://seaweedfs-s3.seaweedfs:8333" | string
		region:   *"eu-west-1" | string
	}

	// Authentication - Keycloak Configuration
	// All admin-client fields are SENSITIVE — see #SecretFieldRegistry.
	// Inline values require `secrets.keycloak.manage: true` (or use existingSecret).
	auth: keycloak: {
		url:   *"http://keycloak.keycloak:8280" | string
		realm: *"kvasir" | string
		"admin-client": {
			"grant-type": *"client_credentials" | "password" // Only client credentials grant is supported for the admin client.
			"server-url": *url | string
			"realm":      *realm | string
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

// Define the source mode explicitly to prevent structural guessing.
#SecretMode: "existing" | "managed" | "none"

#SecretField: {
	// The target environment variable name this field injects into the pod.
	env: string

	// 2. Per-field override: Point to an completely different secret/key.
	ref?: {
		name: string
		key:  string
	}

	// 3. Sensitive inline value. Used ONLY when mode == "managed".
	inlineValue?: string
}

#IntegrationSecret: {
	// Mode selection: Defaults to "none" (secrets disabled).
	// WARNING: When mode is "none", secrets for this integration are not configured.
	// For production, use mode: "existing" with secretName, or mode: "managed" with inlineValue.
	mode: #SecretMode | *"none"

	// Enforce defined secretName if mode == "existing"
	if mode == "existing" {secretName: string}

	// Map of configuration keys for this specific integration.
	fields: [string]: #SecretField
}

// Global Secrets schema map
#Secrets: [string]: #IntegrationSecret

#SecretsSchema: {
	#appConfig: #ApplicationConfig

	keycloak: #IntegrationSecret & {
		let _client = #appConfig.auth.keycloak."admin-client"
		fields: {
			if _client."grant-type" == "client_credentials" {
				"admin-client-id": env:     "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_CLIENT_ID"
				"admin-client-secret": env: "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET"
			}
			if _client."grant-type" == "password" {
				"admin-username": env: "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_USERNAME"
				"admin-password": env: "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_PASSWORD"
			}
		}
	}
	s3: #IntegrationSecret & {
		fields: {
			"access-key": env: "KVASIR_STORAGE_S3_ACCESS_KEY"
			"secret-key": env: "KVASIR_STORAGE_S3_SECRET_KEY"
		}
	}
	clickhouse: #IntegrationSecret & {
		fields: {
			user: env:     "KVASIR_KG_CLICKHOUSE_USER"
			password: env: "KVASIR_KG_CLICKHOUSE_PASSWORD"
		}
	}
	policyEnforcer: #IntegrationSecret & {
		mode: "none"
		fields: {
			"basic-auth-password": env: "KVASIR_POD_AUTH_HTTP_ENDPOINT_POLICY_ENFORCER_BASIC_AUTH_PASSWORD"
			"api-key-value": env:       "KVASIR_POD_AUTH_HTTP_ENDPOINT_POLICY_ENFORCER_API_KEY_KEY_VALUE"
		}
	}}

// Module-managed Secret name for a given integration. Stable function of the
// instance name so users can reference it from external tooling if needed.
#ManagedSecretName: {
	instanceName: string
	integration:  string
	out:          "\(instanceName)-\(integration)-secret"
}
