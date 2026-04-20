// neg-inline-no-source.cue — sets a sensitive field inline with NO secret
// source configured. Expected: vet FAILS with "sets sensitive field … inline".

values: {
	applicationConfig: {
		kg: clickhouse: {
			host:     "clickhouse.example.svc.cluster.local"
			port:     8123
			user:     "test-user"
			password: "leaked-into-configmap"
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
}
