# 02-knowledge-graph.hurl
# Scenario: knowledge-graph changes and querying for user "bob".
# Registers a pod, inserts a Person entity via a JSON-LD change, polls until
# the change is COMMITTED, then verifies the entity is returned by GraphQL.

POST {{kvasir_url}}/
Content-Type: application/ld+json
```json
{
  "@context": {"@vocab": "{{kss_vocab}}"},
  "name": "bob-{{ts}}",
  "ownerUserId": "bob-{{ts}}",
  "adminClientId": "bob-{{ts}}",
  "adminClientSecret": "test-secret"
}
```

HTTP 201

POST {{keycloak_url}}/realms/{{realm}}/protocol/openid-connect/token
[FormParams]
grant_type: client_credentials
client_id: bob-{{ts}}
client_secret: test-secret
[Options]
retry: 10
retry-interval: 1000

HTTP 200
[Captures]
token: jsonpath "$.access_token"

GET {{kvasir_url}}/bob-{{ts}}
Authorization: Bearer {{token}}
[Options]
retry: 10
retry-interval: 1000

HTTP 200

# Insert a Person entity
POST {{kvasir_url}}/bob-{{ts}}/changes
Content-Type: application/ld+json
Authorization: Bearer {{token}}
```json
{
  "@context": {
    "@vocab": "{{kss_vocab}}",
    "ex": "http://example.org/",
    "so": "http://schema.org/"
  },
  "insert": [{
    "@id": "http://example.org/person-bob-{{ts}}",
    "@type": "ex:Person",
    "so:givenName": "Bob Jones",
    "so:email": "bob-{{ts}}@kvasir-test.local"
  }]
}
```

HTTP 201
[Captures]
change_url: header "Location"

# Poll until the change processor until not pending and a redirect to the change status is returned
GET {{change_url}}
Authorization: Bearer {{token}}
Accept: application/ld+json
[Options]
retry: 30
retry-interval: 1000

HTTP 303
[Asserts]
header "location" not contains "pending"
header "location" matches "{{kvasir_url}}/bob-{{ts}}/changes/[0-9a-f-]+"

# Follow the redirect to get the change status
GET {{change_url}}
Authorization: Bearer {{token}}
Accept: application/ld+json
[Options]
location: true

HTTP 200
[Asserts]
body contains "COMMITTED"

# Verify the entity is queryable via GraphQL
POST {{kvasir_url}}/bob-{{ts}}/query
Content-Type: application/json
Authorization: Bearer {{token}}
```json
{
  "@context": {"ex": "http://example.org/", "so": "http://schema.org/"},
  "query": "{ ex_Person(id: \"http://example.org/person-bob-{{ts}}\") { id so_givenName } }"
}
```

HTTP 200
[Asserts]
jsonpath "$.data.ex_Person[0].so_givenName[0]" == "Bob Jones"

# Cleanup
DELETE {{kvasir_url}}/bob-{{ts}}
Authorization: Bearer {{token}}

HTTP *
[Asserts]
status >= 200
status < 500
