// pos-perfield-ref.cue — per-field ref overrides on top of existingSecret.
// Expected: vet succeeds; build emits NO managed Secrets; refs point at the
// per-field Secret/key for overridden fields and at the integration's
// existingSecret with custom keys for the rest (External Secrets Operator
// style key remapping).

values: {
	applicationConfig: {
		kg: clickhouse: {
			host: "clickhouse.example.svc.cluster.local"
			port: 8123
		}

		messaging: kafka: "bootstrap-servers": "kafka.example.svc.cluster.local:9092"

		storage: s3: {
			endpoint: "http://s3.example.svc.cluster.local:8333"
			region:   "us-east-1"
		}

		auth: keycloak: {
			url:   "http://keycloak.example.svc.cluster.local:8080"
			realm: "kvasir"
			"admin-client": {
				"server-url": "http://keycloak.example.svc.cluster.local:8080"
				"realm":      "master"
			}
		}

		pep: openfga: url: "http://openfga.example.svc.cluster.local:8080"
	}

	secrets: {
		keycloak: {
			existingSecret: "test-keycloak"
			fields: {
				adminPassword: ref: {
					name: "platform-keycloak-admin"
					key:  "password"
				}
				adminClientSecret: ref: {
					name: "platform-keycloak-admin"
					key:  "client-secret"
				}
			}
		}
		s3: {
			existingSecret: "test-s3-vault-style"
			fields: {
				accessKey: key: "AWS_ACCESS_KEY_ID"
				secretKey: key: "AWS_SECRET_ACCESS_KEY"
			}
		}
		clickhouse: existingSecret: "test-clickhouse"
	}
}
