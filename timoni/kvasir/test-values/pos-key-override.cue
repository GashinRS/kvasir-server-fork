values: {
	secrets: {
		keycloak: {
			secretName: "my-keycloak-secret"
			fields: {
				"admin-client-id": key:     "client_id"
				"admin-client-secret": key: "client_secret"
			}
		}
		s3: {
			secretName: "my-s3-secret"
		}
		clickhouse: {
			secretName: "my-clickhouse-secret"
			fields: {
				user: key: "username"
			}
		}
	}
}
