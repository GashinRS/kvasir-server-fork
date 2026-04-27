# 03-slices.hurl
# Scenario: GraphQL slices for user "carol".
# Registers a pod, inserts a Person entity, waits for it to be COMMITTED,
# then creates a named GraphQL slice, queries data through it, and deletes
# the slice before cleaning up the pod.

POST {{kvasir_url}}/
Content-Type: application/ld+json
```json
{
  "@context": {"@vocab": "{{kss_vocab}}"},
  "name": "carol-{{ts}}",
  "ownerUserId": "carol-{{ts}}",
  "adminClientId": "carol-{{ts}}",
  "adminClientSecret": "test-secret"
}
```

HTTP 201

POST {{keycloak_url}}/realms/{{realm}}/protocol/openid-connect/token
[FormParams]
grant_type: client_credentials
client_id: carol-{{ts}}
client_secret: test-secret
[Options]
retry: 10
retry-interval: 1000

HTTP 200
[Captures]
token: jsonpath "$.access_token"

GET {{kvasir_url}}/carol-{{ts}}
Authorization: Bearer {{token}}
[Options]
retry: 10
retry-interval: 1000

HTTP 200

# Insert a Person entity that the slice will expose
POST {{kvasir_url}}/carol-{{ts}}/changes
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
    "@id": "http://example.org/person-carol-{{ts}}",
    "@type": "ex:Person",
    "so:givenName": "Carol White",
    "so:email": "carol-{{ts}}@kvasir-test.local"
  }]
}
```

HTTP 201
[Captures]
change_url: header "Location"

GET {{change_url}}
Authorization: Bearer {{token}}
Accept: application/ld+json
[Options]
retry: 30
retry-interval: 1000

HTTP 303
[Asserts]
header "location" not contains "pending"
header "location" matches "{{kvasir_url}}/carol-{{ts}}/changes/[0-9a-f-]+"

# Follow the redirect to get the change status
GET {{change_url}}
Authorization: Bearer {{token}}
Accept: application/ld+json
[Options]
location: true

HTTP 200
[Asserts]
body contains "COMMITTED"

# Create a named GraphQL slice
# Note: @context in the body is the slice's own namespace context (JSON-LD wire format)
POST {{kvasir_url}}/carol-{{ts}}/slices
Content-Type: application/ld+json
Authorization: Bearer {{token}}
```json
{
  "@context": {
    "kss": "{{kss_vocab}}",
    "ex": "http://example.org/",
    "so": "http://schema.org/"
  },
  "kss:name": "persons-carol-{{ts}}",
  "kss:schema": {
    "@type": "kss:EmbeddedSliceSchema",
    "kss:sdl": "type Query { persons: [ex_Person!]! } type ex_Person { id: ID! so_givenName: String! }"
  }
}
```

HTTP 201
[Captures]
slice_url: header "Location"

# Query data through the slice
POST {{slice_url}}/query
Content-Type: application/json
Authorization: Bearer {{token}}
```json
{"query": "{ persons { id so_givenName } }"}
```

HTTP 200
[Asserts]
jsonpath "$.data.persons[0].so_givenName" == "Carol White"

# Delete the slice before pod cleanup
DELETE {{slice_url}}
Authorization: Bearer {{token}}

HTTP 204

# Cleanup
DELETE {{kvasir_url}}/carol-{{ts}}
Authorization: Bearer {{token}}

HTTP *
[Asserts]
status >= 200
status < 500
