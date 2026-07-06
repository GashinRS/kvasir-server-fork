// pos-mixed.cue — multiple resolution paths active simultaneously:
//   - keycloak: existingSecret + per-field ref overrides
//   - s3:       existingSecret + custom upstream key names
//   - clickhouse: managed (inline values, manage: true)
// Expected: vet succeeds; build emits exactly ONE managed Secret (clickhouse);
// keycloak/s3 env refs follow rules above.

values: {
	secrets: {
		keycloak: {
			mode:       "existing"
			secretName: "test-keycloak"
			fields: {
				"admin-client-secret": ref: {
					name: "test-keycloak-shared"
					key:  "client-secret"
				}
			}
		}
		s3: {
			mode: "none"
		}
		clickhouse: {
			mode: "managed"
			fields: {
				"user": inlineValue:     "chUser"
				"password": inlineValue: "chPass"
			}
		}
	}
}
