// pos-mixed.cue — multiple resolution paths active simultaneously:
//   - keycloak: existingSecret + per-field ref overrides
//   - s3:       existingSecret + custom upstream key names
//   - clickhouse: managed (inline values, manage: true)
// Expected: vet succeeds; build emits exactly ONE managed Secret (clickhouse);
// keycloak/s3 env refs follow rules above.

values: {
	applicationConfig: {
		kg: clickhouse: {
			host:     "clickhouse.example.svc.cluster.local"
			port:     8123
			user:     "test-user"
			password: "test-password"
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
				adminClientSecret: ref: {
					name: "test-keycloak-shared"
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
		clickhouse: manage: true
	}
}
