# 04-s3.hurl
# Scenario: S3 object storage for user "dave".
# Registers a pod, uploads a file, verifies it appears in the bucket listing,
# downloads and checks the content, deletes the object, then cleans up the pod.

POST {{kvasir_url}}/
Content-Type: application/ld+json
```json
{
  "@context": {"@vocab": "{{kss_vocab}}"},
  "name": "dave-{{ts}}",
  "ownerUserId": "dave-{{ts}}",
  "adminClientId": "dave-{{ts}}",
  "adminClientSecret": "test-secret"
}
```

HTTP 201

POST {{keycloak_url}}/realms/{{realm}}/protocol/openid-connect/token
[FormParams]
grant_type: client_credentials
client_id: dave-{{ts}}
client_secret: test-secret
[Options]
retry: 10
retry-interval: 1000

HTTP 200
[Captures]
token: jsonpath "$.access_token"

GET {{kvasir_url}}/dave-{{ts}}
Authorization: Bearer {{token}}
[Options]
retry: 10
retry-interval: 1000

HTTP 200

# Wait for the S3/OpenFGA auth path to be ready before uploading.
# OpenFGA auth model creation is async after pod creation; a bodyless GET
# retried here ensures the model exists before the PUT body can be consumed.
GET {{kvasir_url}}/dave-{{ts}}/s3/?list-type=2
Authorization: Bearer {{token}}
[Options]
retry: 10
retry-interval: 1000

HTTP 200

# Upload a text file
PUT {{kvasir_url}}/dave-{{ts}}/s3/api-test-dave-{{ts}}.txt
Authorization: Bearer {{token}}
```
Kvasir hurl test content - {{ts}}
```

HTTP 200

# Verify the file appears in the bucket listing
GET {{kvasir_url}}/dave-{{ts}}/s3/?list-type=2
Authorization: Bearer {{token}}

HTTP 200
[Asserts]
body contains "api-test-dave-{{ts}}.txt"

# Download and verify content
GET {{kvasir_url}}/dave-{{ts}}/s3/api-test-dave-{{ts}}.txt
Authorization: Bearer {{token}}

HTTP 200
[Asserts]
body contains "{{ts}}"

# Delete the object
DELETE {{kvasir_url}}/dave-{{ts}}/s3/api-test-dave-{{ts}}.txt
Authorization: Bearer {{token}}

HTTP *
[Asserts]
status >= 200
status < 300

# Cleanup
DELETE {{kvasir_url}}/dave-{{ts}}
Authorization: Bearer {{token}}

HTTP *
[Asserts]
status >= 200
status < 500
