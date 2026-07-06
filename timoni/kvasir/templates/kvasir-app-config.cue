package templates

#ApplicationConfig: {
	_name:      string
	_namespace: string

	http: {
		"base-uri":      *"http://\(_name).\(_namespace).svc.cluster.local:8080/" | string
		"webclient-uri": *"http://\(_name).\(_namespace).svc.cluster.local:8080/_ui/" | string
	}

	pod?: {
		"default-context"?: string
		"auto-ingest-rdf"?: bool

		auth?: {
			"enable-solid-web-id"?: bool
			"require-dpop"?:        bool
			"skip-dpop-ath-check"?: bool

			oidc?: {
				"server-url"?: string
				"principal-extractor"?: {
					"class-name"?: string
					config?:       string
				}
				"jwt-allowed-clock-skew-seconds"?: int
			}

			uma?: {
				"server-url"?: string
				"principal-extractor"?: {
					"class-name"?: string
					config?:       string
				}
				"jwt-allowed-clock-skew-seconds"?: int
			}

			"http-endpoint-policy-enforcer"?: {
				url?: string
				"basic-auth"?: {
					username?: string
					password?: string
				}
				"api-key"?: {
					"key-name"?:  string
					"key-value"?: string
					"send-via"?:  "header" | "query"
				}
			}
		}
	}

	bootstrap?: {
		pods?: [...{
			name:                                           string
			"owner-user-id"?:                               string
			"auto-register-uma"?:                           bool
			"auto-register-http-endpoint-policy-enforcer"?: bool
			"generate-clients"?: [...{
				"client-id":               string
				"enable-service-account"?: bool
				"client-secret"?:          string
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

	kg: clickhouse: {
		host: *"clickhouse.clickhouse.svc.cluster.local" | string
		port: *8123 | int
	}

	messaging: kafka: {
		"bootstrap-servers": *"kafka.kafka:9092" | string
	}

	storage: s3: {
		endpoint: *"http://seaweedfs-s3.seaweedfs:8333" | string
		region:   *"eu-west-1" | string
	}

	auth: keycloak: {
		url:   *"http://keycloak.keycloak:8280" | string
		realm: *"kvasir" | string
		"admin-client": {
			"grant-type":      *"client_credentials" | "password"
			"server-url":      *url | string
			"realm":           *realm | string
			"client-id"?:      string
			"client-secret"?:  string
			"username"?:       string
			"password"?:       string
		}
	}

	pep: openfga: {
		url: *"http://openfga.openfga:8380" | string
	}
}

#QuarkusConfig: {
	log?: {
		level?: "FATAL" | "ERROR" | "WARN" | "INFO" | "DEBUG" | "TRACE"
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

#SecretField: {
	env:         string
	secretName?: string
	key?:        string
	value?:      string
}

#IntegrationSecret: {
	secretName?: string
	fields: [string]: #SecretField
}

#Secrets: [string]: #IntegrationSecret

#SecretsSchema: {
	#appConfig: #ApplicationConfig

	keycloak: #IntegrationSecret & {
		fields: {
			"admin-client-id": env:     "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_CLIENT_ID"
			"admin-client-secret": env: "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_CLIENT_SECRET"
			"admin-username": env:      "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_USERNAME"
			"admin-password": env:      "KVASIR_AUTH_KEYCLOAK_ADMIN_CLIENT_PASSWORD"
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
		fields: {
			"basic-auth-password": env: "KVASIR_POD_AUTH_HTTP_ENDPOINT_POLICY_ENFORCER_BASIC_AUTH_PASSWORD"
			"api-key-value": env:       "KVASIR_POD_AUTH_HTTP_ENDPOINT_POLICY_ENFORCER_API_KEY_KEY_VALUE"
		}
	}
}

#ManagedSecretName: {
	instanceName: string
	integration:  string
	out:          "\(instanceName)-\(integration)-secret"
}
