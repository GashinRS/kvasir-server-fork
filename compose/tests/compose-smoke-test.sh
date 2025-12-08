#!/bin/bash

DOCKER_HOST=${1:-localhost}

# Smoke test runs in docker enabled runner, localhost does not work here, use 'docker'
overviewResponse=$(curl -H "Accept:application/ld+json" -s http://${DOCKER_HOST}:8080)
#{"@context":{"kss":"https://kvasir.discover.ilabt.imec.be/vocab#"},"@graph":[{"@id":"http://localhost:8080/alice"}]}
context=$(echo "$overviewResponse" | jq -r '.["@context"]["kss"]')
aliceId=$(echo "$overviewResponse" | jq -r '.["@graph"][0]["@id"]')

if [[ "$context" == "https://kvasir.discover.ilabt.imec.be/vocab#" && "$aliceId" == "http://localhost:8080/alice" ]]; then
  echo "Overview test passed!"
else
  echo "Overview test failed!"
  exit 1
fi

# Replace all occurrences of localhost with docker hostname
alicePod=http://${DOCKER_HOST}:8080/alice

echo "Testing alice pod should return challenge: $alicePod"
resp_headers=$(curl -s -D - -o /dev/null "$alicePod")

# extract HTTP status code from the status line
status=$(echo "$resp_headers" | head -n 1 | awk '{print $2}')

# check for Www-Authenticate header (case-insensitive)
if echo "$resp_headers" | grep -qi '^WWW-Authenticate:' && [[ "$status" == "401" ]]; then
  echo "Alice pod test passed!"
else
  echo "Alice pod test failed!"
  exit 1
fi

ui_response=$(curl -s -o /dev/null -w "%{http_code}" http://${DOCKER_HOST}:8080/_ui/)

if [[ "$ui_response" == "200" ]]; then
  echo "Kvasir UI test passed!"
else
  echo "Kvasir UI test failed!"
  exit 1
fi

