#!/bin/bash

set -eo pipefail

# Check if argument is provided, set KVASIR_HOST to the argument if it is
# Otherwise, check if KVASIR_HOST is already set as env var, if not exit with appropriate message
#
if [ -z "$1" ]; then
  if [ -z "$KVASIR_HOST" ]; then
    echo "Please provide the KVASIR_HOST as an argument or set it as an environment variable"
    exit 1
  fi
else
  KVASIR_HOST=$1
fi

# Check Kvasir connectivity, should return 200 OK and Json response
curl -s -X GET "http://$KVASIR_HOST/" -H "accept: application/ld+json" | jq

CREDENTIALS=$(echo "confidential-client:sQaCSaWG2ODLWOQzkcStPhAWn6mtCV4z" | base64)
