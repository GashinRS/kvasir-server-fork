// dev-values.cue — DEV/LOCAL ONLY overlay.
//
// Provides placeholder credentials for local clusters (kind, k3d, minikube).
// The module materializes Secrets from inline `value` fields, scrubs them from
// the ConfigMap, and injects them via env `secretKeyRef` into the Deployment.
//
// DO NOT USE IN PRODUCTION. The generated Secrets contain well-known
// placeholders and offer zero security.
//
// Apply with:
//   timoni -n kvasir apply kvasir oci://<registry>/kvasir \
//     --values ./dev-values.cue
//
// Or render to inspect:
//   timoni build dev ./timoni/kvasir --namespace kvasir \
//     --values ./timoni/kvasir/dev-values.cue --output yaml

values: {
	applicationConfig: {
		kg: clickhouse: {
			host: "clickhouse.kvasir.svc.cluster.local"
			port: 8123
		}

		messaging: kafka: "bootstrap-servers": "kafka.kvasir.svc.cluster.local:9092"

		storage: s3: {
			endpoint: "http://seaweedfs-s3.kvasir.svc.cluster.local:8333"
			region:   "us-east-1"
		}

		auth: keycloak: {
			url:   "http://keycloak.kvasir.svc.cluster.local:8080"
			realm: "kvasir"
			"admin-client": {
				"grant-type": "password"
				"server-url": "http://keycloak.kvasir.svc.cluster.local:8080"
				"realm":      "master"
				"client-id":  "admin-cli"
			}
		}

		pep: openfga: url: "http://openfga.kvasir.svc.cluster.local:8080"
	}

	secrets: {
		keycloak: fields: {
			"admin-username": value:      "admin"
			"admin-password": value:      "dev-admin-password"
			"admin-client-id": value:     "admin-cli"
			"admin-client-secret": value: "dev-admin-client-secret"
		}
		s3: fields: {
			"access-key": value: "dev-access-key"
			"secret-key": value: "dev-secret-key"
		}
		clickhouse: fields: {
			user: value:     "default"
			password: value: "dev-clickhouse-password"
		}
	}
}
