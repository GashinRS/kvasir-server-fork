// pos-managed.cue — every integration uses module-managed Secrets.
// Expected: vet succeeds; build emits 3 Secrets (keycloak, s3, clickhouse),
// scrubs sensitive keys from the ConfigMap, wires env via secretKeyRef.
//
// No `package` declaration — consumed only via `--values`.

values: {
	applicationConfig: auth: keycloak: "admin-client": "grant-type": "password"

	secrets: {
		keycloak: {
			mode: "managed"
			fields: {
				"admin-username": inlineValue: "kcadmin"
				"admin-password": inlineValue: "kcadminpassword"
			}
		}
		s3: {
			mode: "managed"
			fields: {
				"access-key": inlineValue: "test-ak"
				"secret-key": inlineValue: "test-sk"
			}
		}
		clickhouse: {
			mode: "managed"
			fields: {
				"user": inlineValue:     "chuser"
				"password": inlineValue: "chpassword"
			}
		}
	}
}
