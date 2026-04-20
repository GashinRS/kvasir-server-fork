// pos-existing-secret.cue — every integration sources from existing Secrets,
// no inline sensitive values. Expected: vet succeeds; build emits NO managed
// Secrets; Deployment env uses secretKeyRef pointing at "test-*" Secrets with
// default keys (admin-password, access-key, password, etc.).

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
		keycloak: existingSecret:   "test-keycloak"
		s3: existingSecret:         "test-s3"
		clickhouse: existingSecret: "test-clickhouse"
	}
}
