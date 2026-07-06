// neg-manage-empty.cue — `manage: true` with NO inline values for that
// integration. Expected: vet FAILS with "requires at least one inline value".

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
		clickhouse: mode: "managed"
		keycloak: mode:   "none"
		s3: mode:         "none"
	}

}
