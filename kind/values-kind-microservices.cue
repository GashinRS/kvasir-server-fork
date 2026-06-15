package main

values: {
	deploymentMode: "microservices"

	image: {
		tag:        "latest"
		pullPolicy: "IfNotPresent"
	}

	init: {
		enabled:        true
		exitAfterSetup: true
	}

	ingress: {
		enabled: true
		host:    "kvasir.localhost"
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
					"username":   "admin"
					"password":   "admin"
					realm:        "master"
				}
			}
		}

		storage: {
			s3: {
				endpoint:     "http://seaweedfs-s3.seaweedfs:8333"
				"access-key": "kvasir"
				"secret-key": "kvasirkvasir"
			}
		}
	}

	secrets: {
		keycloak: manage: true
		s3: manage:       true
	}
}
