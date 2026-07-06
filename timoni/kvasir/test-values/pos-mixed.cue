values: {
	secrets: {
		keycloak: {
			secretName: "kvasir-keycloak"
			fields: {
				"admin-client-secret": {
					secretName: "platform-secrets"
					key:        "keycloak-client-secret"
				}
			}
		}
		s3: {}
		clickhouse: {
			fields: {
				user: value:     "dev-user"
				password: value: "dev-password"
			}
		}
	}
}
