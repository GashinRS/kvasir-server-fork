values: {
	secrets: {
		keycloak: {
			secretName: "kvasir-keycloak"
			fields: {
				"admin-client-secret": secretName: "separate-secret"
			}
		}
		s3: {
			secretName: "kvasir-s3"
			fields: {
				"access-key": value: "dev-access-key"
			}
		}
		clickhouse: secretName: "kvasir-clickhouse"
	}
}
