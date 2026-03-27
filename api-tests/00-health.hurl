# 00-health.hurl
# Scenario: basic connectivity checks that require no auth and no pod.
# Verifies the Kvasir API is up and the UI is being served.

# API root returns a JSON-LD overview of available pods
GET {{kvasir_url}}/
Accept: application/ld+json

HTTP 200
[Asserts]
header "Content-Type" contains "application/ld+json"
jsonpath "$['@context']['kss']" == "{{kss_vocab}}"

# UI is served and responsive
GET {{kvasir_url}}/_ui/

HTTP 200

# Check if text/html redirects to the UI
GET {{kvasir_url}}
Accept: text/html

HTTP *
Location: {{kvasir_url}}/_ui/
[Asserts]
status > 300
status < 400

# Follow the redirect to the UI
GET {{kvasir_url}}
Accept: text/html
[Options]
location: true

HTTP 200
