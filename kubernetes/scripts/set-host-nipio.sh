#!/bin/bash

# Linux/macOS/WSL2
HOST_IP=$(ip route get 1 | awk '{print $(NF-2);exit}' | tr -d '\n' | tr '.' '-')

export KEYCLOAK_HOST="keycloak-$HOST_IP.nip.io"
export KVASIR_HOST="kvasir-$HOST_IP.nip.io"

echo "$KEYCLOAK_HOST"
echo "$KVASIR_HOST"
# Generate self-signed certificates
CERT_DIR="./.certs"
mkdir -p $CERT_DIR

mkcert -cert-file=$CERT_DIR/tls.crt -key-file=$CERT_DIR/tls.key "$KEYCLOAK_HOST" "$KVASIR_HOST"

# Check if secrets already exist
#
if kubectl get secret $KEYCLOAK_HOST-tls -n keycloak &>/dev/null; then
  echo "Secret $KEYCLOAK_HOST-tls already exists in namespace keycloak"
else
  kubectl create namespace keycloak || true
  kubectl create secret tls $KEYCLOAK_HOST-tls \
    --cert=$CERT_DIR/tls.crt \
    --key=$CERT_DIR/tls.key \
    -n keycloak
fi

if kubectl get secret $KVASIR_HOST-tls -n kvasir &>/dev/null; then
  echo "Secret $KVASIR_HOST-tls already exists in namespace kvasir"
else
  kubectl create namespace kvasir || true
  kubectl create secret tls $KVASIR_HOST-tls \
    --cert=$CERT_DIR/tls.crt \
    --key=$CERT_DIR/tls.key \
    -n kvasir
fi

if kubectl get secret oidc-truststore-secret -n kvasir &>/dev/null; then
  echo "Secret oidc-truststore-secret already exists in namespace kvasir"
else
  MKCERT_CA_PEM="$(mkcert -CAROOT)/rootCA.pem"
  TRUSTSTORE_FILE="$CERT_DIR/oidc-truststore.p12"
  TRUSTSTORE_PASSWORD="changeit"
  # If file doesnt exist create it
  if [ ! -f "$TRUSTSTORE_FILE" ]; then
    keytool -importcert -alias mkcert-root-ca \
      -file "$MKCERT_CA_PEM" \
      -keystore "$TRUSTSTORE_FILE" \
      -storetype PKCS12 \
      -storepass "$TRUSTSTORE_PASSWORD" \
      -noprompt
  fi

  kubectl create secret generic oidc-truststore-secret \
    --from-file=$TRUSTSTORE_FILE \
    --from-literal=oidc-truststore-password=$TRUSTSTORE_PASSWORD \
    -n kvasir
fi
