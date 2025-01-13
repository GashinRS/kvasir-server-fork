#!/bin/bash

overviewResponse=$(curl -s http://localhost:8080)
#{"@context":{"kss":"https://kvasir.discover.ilabt.imec.be/vocab#"},"@graph":[{"@id":"http://localhost:8080/alice","kss:profile":"http://localhost:8080/alice/.profile"}]}
context=$(echo "$overviewResponse" | jq -r '.["@context"]["kss"]')
aliceId=$(echo "$overviewResponse" | jq -r '.["@graph"][0]["@id"]')
aliceProfile=$(echo "$overviewResponse" | jq -r '.["@graph"][0]["kss:profile"]')

if [[ "$context" == "https://kvasir.discover.ilabt.imec.be/vocab#" && "$aliceId" == "http://localhost:8080/alice" && "$aliceProfile" == "http://localhost:8080/alice/.profile" ]]; then
  echo "Overview test passed!"
else
  echo "Overview test failed!"
  exit 1
fi

echo "Testing alice .profile: $aliceProfile"
aliceProfileResponse=$(curl -s $aliceProfile)
#{"@id":"http://localhost:8080/alice/.profile","kss:authServerUrl":"http://localhost:8280/realms/alice","@context":{"kss":"https://kvasir.discover.ilabt.imec.be/vocab#"}}
authServerUrl=$(echo "$aliceProfileResponse" | jq -r '.["kss:authServerUrl"]')
context=$(echo "$aliceProfileResponse" | jq -r '.["@context"]["kss"]')
aliceProfileId=$(echo "$aliceProfileResponse" | jq -r '.["@id"]')

if [[ "$authServerUrl" == "http://localhost:8280/realms/alice" && "$context" == "https://kvasir.discover.ilabt.imec.be/vocab#" && "$aliceProfileId" == "http://localhost:8080/alice/.profile" ]]; then
  echo "Alice profile test passed!"
else
  echo "Alice profile test failed!"
  exit 1
fi

ui_response=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081)

if [[ "$ui_response" == "200" ]]; then
  echo "Kvasir UI test passed!"
else
  echo "Kvasir UI test failed!"
  exit 1
fi