# 01-pod-lifecycle.hurl
# Scenario: basic pod lifecycle for user "alice".
# Registers a pod (no pre-auth needed), acquires a service-account token via
# client_credentials, waits for full initialisation, verifies the pod config
# is served as JSON-LD, then deletes the pod.
# Kvasir's cleanupForPod hook also removes the Keycloak user automatically.

POST {{kvasir_url}}/
Content-Type: application/ld+json
```json
{
  "@context": {"@vocab": "{{kss_vocab}}"},
  "name": "alice-{{ts}}",
  "ownerUserId": "alice-{{ts}}",
  "adminClientId": "alice-{{ts}}",
  "adminClientSecret": "test-secret"
}
```

HTTP 201

# Retry until Keycloak finishes provisioning the client
POST {{keycloak_url}}/realms/{{realm}}/protocol/openid-connect/token
[FormParams]
grant_type: client_credentials
client_id: alice-{{ts}}
client_secret: test-secret
[Options]
retry: 10
retry-interval: 1000

HTTP 200
[Captures]
token: jsonpath "$.access_token"

# Wait for pod initialisation to complete (OpenFGA store + Keycloak wiring)
GET {{kvasir_url}}/alice-{{ts}}
Authorization: Bearer {{token}}
[Options]
retry: 10
retry-interval: 1000

HTTP 200

# Verify the pod config is served as JSON-LD
GET {{kvasir_url}}/alice-{{ts}}
Authorization: Bearer {{token}}
Accept: application/ld+json

HTTP 200
[Asserts]
header "Content-Type" contains "application/ld+json"

# Cleanup: delete pod (also removes the Keycloak user)
DELETE {{kvasir_url}}/alice-{{ts}}
Authorization: Bearer {{token}}

HTTP *
[Asserts]
status >= 200
status < 500
