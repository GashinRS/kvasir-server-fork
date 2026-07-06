// pos-perfield-ref.cue — per-field ref overrides on top of existingSecret.
// Expected: vet succeeds; build emits NO managed Secrets; refs point at the
// per-field Secret/key for overridden fields and at the integration's
// existingSecret with custom keys for the rest (External Secrets Operator
// style key remapping).

values: {
	secrets: {
		keycloak: {
			mode:       "existing"
			secretName: "test-keycloak"
			fields: {
				"admin-client-secret": ref: {
					name: "platform-keycloak-admin"
					key:  "client-secret"
				}
			}
		}
		s3: {
			mode: "none"
		}
		clickhouse: {
			mode: "none"
		}
	}
}
