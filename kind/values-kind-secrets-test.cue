package main

// Test values file exercising the new secrets framework against the kind
// dependencies. Designed to verify three resolution paths simultaneously:
//
//   - Keycloak admin-client: sourced from a Secret with default keys
//     (kind-keycloak-creds), with adminClientSecret overridden via per-field ref
//     to a SEPARATE Secret (kind-keycloak-shared-clientsecret) — exercises
//     mixed integration-Secret + per-field-ref precedence.
//   - S3: sourced from a Secret with CUSTOM upstream key names — exercises
//     ESO/OpenBAO-style key remapping (mimics what Vault/AWS Secrets Manager
//     would dictate).
//   - ClickHouse: left fully inline — exercises dev-fallback path (no Secret,
//     credentials in applicationConfig only).
//
// Also exercises:
//   - startupProbe default (/q/health/live, k8s-aligned)
//   - Custom startupProbe budget for kind cold-start tolerance
//
// Companion Secrets are created by the deployment script before `timoni apply`.

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
		]

		auth: {
			keycloak: {
				url: "http://keycloak.localhost"
				"admin-client": {
					"server-url": "http://keycloak.keycloak:8280"
				}
			}
		}
	}

	secrets: {
		keycloak: {
			existingSecret: "kind-keycloak-creds"
			fields: {
				adminClientSecret: ref: {
					name: "kind-keycloak-shared-clientsecret"
					key:  "client-secret"
				}
			}
		}
		s3: {
			existingSecret: "kind-s3-creds-vault-style"
			fields: {
				accessKey: key: "AWS_ACCESS_KEY_ID"
				secretKey: key: "AWS_SECRET_ACCESS_KEY"
			}
		}
	}

	startupProbe: {
		periodSeconds:    10
		failureThreshold: 60
	}
}
