#!/bin/bash

# Default values
KEYCLOAK_URL=""
REALM=""
USERNAME=""
PASSWORD=""
INITIAL_TOKEN=""
CONFIG_DIR="$HOME/.keycloak"
CONFIG_FILE=""

# Function to show usage
usage() {
    echo "Usage: $0 --keycloak-url <URL> --realm <REALM> --username <USER> --password <PASSWORD> [--initial-token <TOKEN>]"
    exit 1
}

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --keycloak-url) KEYCLOAK_URL="$2"; shift ;;
        --realm) REALM="$2"; shift ;;
        --username) USERNAME="$2"; shift ;;
        --password) PASSWORD="$2"; shift ;;
        --initial-token) INITIAL_TOKEN="$2"; shift ;;
        *) echo "Unknown parameter: $1"; usage ;;
    esac
    shift
done

# Check if required parameters are set
if [[ -z "$KEYCLOAK_URL" || -z "$REALM" || -z "$USERNAME" || -z "$PASSWORD" ]]; then
    usage
fi

# Prepare config file path
CONFIG_FILE="$CONFIG_DIR/${REALM}-${USERNAME}.json"

# Reuse client ID and secret if config file exists
if [[ -f "$CONFIG_FILE" ]]; then
    echo "🔄 Reusing existing client configuration from $CONFIG_FILE..."
    CLIENT_ID=$(jq -r '.client_id' "$CONFIG_FILE")
    CLIENT_SECRET=$(jq -r '.client_secret' "$CONFIG_FILE")
else
    # Ensure config directory exists
    mkdir -p "$CONFIG_DIR"

    # Check if initial token is provided
    if [[ -z "$INITIAL_TOKEN" ]]; then
        echo "Error: Initial token is required for first-time client registration."
        usage
    fi

    echo "📌 Registering a new client with Keycloak..."

    # Register a new client dynamically
    CLIENT_RESPONSE=$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/clients-registrations/openid-connect" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $INITIAL_TOKEN" \
        -d '{
            "token_endpoint_auth_method": "client_secret_post",
            "grant_types": ["password"],
            "response_types": ["token"]
        }')

    # Extract client_id and client_secret
    CLIENT_ID=$(echo "$CLIENT_RESPONSE" | jq -r '.client_id')
    CLIENT_SECRET=$(echo "$CLIENT_RESPONSE" | jq -r '.client_secret')

    # If null or empty, registration failed
    if [[ -z "$CLIENT_ID" || -z "$CLIENT_SECRET" || "$CLIENT_ID" == "null" || "$CLIENT_SECRET" == "null" ]]; then
        echo "❌ Failed to register client. Response: $CLIENT_RESPONSE"
        exit 1
    fi

    echo "✅ Client registered: $CLIENT_ID"

    # Save client ID and secret to config file
    echo "🔒 Saving client configuration to $CONFIG_FILE..."
    echo "{\"client_id\": \"$CLIENT_ID\", \"client_secret\": \"$CLIENT_SECRET\"}" > "$CONFIG_FILE"
fi

# Step 1: Authenticate with the provided password
echo "🔑 Fetching access token with provided password..."
TOKEN_RESPONSE=$(curl -s -X POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "client_id=$CLIENT_ID" \
    -d "client_secret=$CLIENT_SECRET" \
    -d "grant_type=password" \
    -d "username=$USERNAME" \
    -d "password=$PASSWORD")

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token')

if [[ "$ACCESS_TOKEN" == "null" ]]; then
    echo "❌ Failed to fetch access token. Response: $TOKEN_RESPONSE"
    echo "🔗 Please change your password manually at: $KEYCLOAK_URL/realms/$REALM/account"
    exit 1
fi



# Save access token to config file
echo "🔒 Updating $CONFIG_FILE with access token..."
jq --arg access_token "$ACCESS_TOKEN" '.access_token = $access_token' "$CONFIG_FILE" > "${CONFIG_FILE}.tmp" && mv "${CONFIG_FILE}.tmp" "$CONFIG_FILE"


echo "✅ Access token retrieved!"
echo "🔹 Token: $ACCESS_TOKEN"
