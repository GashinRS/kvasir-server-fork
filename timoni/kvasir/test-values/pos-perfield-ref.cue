values: {
	secrets: {
		keycloak: {
			secretName: "test-keycloak"
			fields: {
				"admin-client-secret": {
					secretName: "platform-keycloak-admin"
					key:        "client-secret"
				}
			}
		}
		s3:         secretName: "test-s3"
		clickhouse: secretName: "test-clickhouse"
	}
}
