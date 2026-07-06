// pos-existing-secret.cue — every integration sources from existing Secrets,
// no inline sensitive values. Expected: vet succeeds; build emits NO managed
// Secrets; Deployment env uses secretKeyRef pointing at "test-*" Secrets with
// default keys (admin-password, access-key, password, etc.).

values: {
	secrets: {
		keycloak: {
			mode:       "existing"
			secretName: "test-keycloak"
		}
		s3: {
			mode:       "existing"
			secretName: "test-s3"
		}
		clickhouse: {
			mode:       "existing"
			secretName: "test-clickhouse"
		}
	}
}
