package main

values: {
	serviceName: "monolith"

	image: {
		repository: "gitlab.ilabt.imec.be:4567/kvasir/kvasir-server/monolith"
		tag:        "latest"
		pullPolicy: "IfNotPresent"
	}

	applicationConfig: {
		http: {
			"base-uri":      "http://kvasir.localhost/"
			"webclient-uri": "http://kvasir.localhost/_ui/"
		}

		bootstrap: pods: [
			{
				name: "alice"
				"generate-clients": [{
					"client-id":              "alice-client"
					"client-secret":          "test-secret"
					"enable-service-account": true
				}]
			},
			{
				name: "bob"
				"generate-clients": [{
					"client-id":              "bob-client"
					"client-secret":          "test-secret"
					"enable-service-account": true
				}]
			},
			{
				name: "carol"
				"generate-clients": [{
					"client-id":              "carol-client"
					"client-secret":          "test-secret"
					"enable-service-account": true
				}]
			},
			{
				name: "dave"
				"generate-clients": [{
					"client-id":              "dave-client"
					"client-secret":          "test-secret"
					"enable-service-account": true
				}]
			},
		]

		auth: {
			keycloak: {
				url: "http://keycloak.localhost"
				"admin-client": {
					"server-url": "http://keycloak.keycloak:8280"
					"grant-type": "password"
					realm:        "master"
				}
			}
		}

		storage: {
			s3: {
				endpoint: "http://seaweedfs-s3.seaweedfs:8333"
			}
		}
	}

	secrets: {
		keycloak: {
			mode: "managed"
			fields: {
				"admin-username": inlineValue: "admin"
				"admin-password": inlineValue: "admin"
			}
		}
		s3: {
			mode: "managed"
			fields: {
				"access-key": inlineValue: "kvasir"
				"secret-key": inlineValue: "kvasirkvasir"
			}
		}
		clickhouse: {
			mode: "none"
		}
	}
}
