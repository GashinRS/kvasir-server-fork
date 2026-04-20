// dev-values.cue — DEV/LOCAL ONLY overlay.
//
// Sensitive fields in `applicationConfig` (admin credentials, S3 access keys,
// ClickHouse password, policy-enforcer secrets) carry NO defaults in the
// module schema. Production deployments must wire them via:
//
//   - secrets.<integration>.existingSecret
//   - secrets.<integration>.fields.<field>.ref
//   - secrets.<integration>.manage: true (module emits the Secret)
//
// Setting an inline value without one of those is a vet/build-time error.
//
// This file provides placeholder credentials AND opts every integration into
// `manage: true`, so a `timoni build ... --values ./dev-values.cue` produces
// a complete, applyable bundle for local clusters (kind, k3d, minikube)
// without requiring out-of-band Secret provisioning.
//
// DO NOT USE THESE VALUES IN PRODUCTION. The generated Secrets contain
// well-known placeholders and offer zero security.
//
// Apply with:
//   timoni -n kvasir apply kvasir oci://<registry>/kvasir \
//     --values ./dev-values.cue
//
// Or render to inspect:
//   timoni build dev ./timoni/kvasir --namespace kvasir \
//     --values ./timoni/kvasir/dev-values.cue --output yaml
//
// This file intentionally has no `package` declaration so it is consumed
// only via `--values`, never auto-loaded as part of the module package.
// It is also excluded from the published OCI artifact via `timoni.ignore`.

values: {
	applicationConfig: {
		kg: clickhouse: {
			host:     "clickhouse.kvasir.svc.cluster.local"
			port:     8123
			user:     "default"
			password: "dev-clickhouse-password"
		}

		messaging: kafka: "bootstrap-servers": "kafka.kvasir.svc.cluster.local:9092"

		storage: s3: {
			endpoint:     "http://seaweedfs-s3.kvasir.svc.cluster.local:8333"
			region:       "us-east-1"
			"access-key": "dev-access-key"
			"secret-key": "dev-secret-key"
		}

		auth: keycloak: {
			url:   "http://keycloak.kvasir.svc.cluster.local:8080"
			realm: "kvasir"
			"admin-client": {
				"grant-type":    "password"
				"server-url":    "http://keycloak.kvasir.svc.cluster.local:8080"
				"realm":         "master"
				"client-id":     "admin-cli"
				"client-secret": "dev-admin-client-secret"
				"username":      "admin"
				"password":      "dev-admin-password"
			}
		}

		pep: openfga: url: "http://openfga.kvasir.svc.cluster.local:8080"
	}

	// Module-managed Secrets — one per integration. The module materializes a
	// Secret named `<instance>-<integration>-secret` from the inline values
	// above, scrubs them out of the ConfigMap, and injects them via env
	// `secretKeyRef` into the Deployment.
	//
	// `policyEnforcer` is omitted: those fields are unset above, so leaving
	// `manage` unset (falsy) is the correct "no source needed" state.
	secrets: {
		keycloak: manage:   true
		s3: manage:         true
		clickhouse: manage: true
	}
}
