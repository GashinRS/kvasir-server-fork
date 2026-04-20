// pos-managed.cue — every integration uses module-managed Secrets.
// Expected: vet succeeds; build emits 3 Secrets (keycloak, s3, clickhouse),
// scrubs sensitive keys from the ConfigMap, wires env via secretKeyRef.
//
// No `package` declaration — consumed only via `--values`.

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
			endpoint:     "http://s3.example.svc.cluster.local:8333"
			region:       "us-east-1"
			"access-key": "test-ak"
			"secret-key": "test-sk"
		}

		auth: keycloak: {
			url:   "http://keycloak.example.svc.cluster.local:8080"
			realm: "kvasir"
			"admin-client": {
				"server-url":    "http://keycloak.example.svc.cluster.local:8080"
				"realm":         "master"
				"client-id":     "admin-cli"
				"client-secret": "test-admin-client-secret"
				"username":      "admin"
				"password":      "test-admin-password"
			}
		}

		pep: openfga: url: "http://openfga.example.svc.cluster.local:8080"
	}

	secrets: {
		keycloak: manage:   true
		s3: manage:         true
		clickhouse: manage: true
	}
}
