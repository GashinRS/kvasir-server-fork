#!/bin/bash
set -eo pipefail

CLUSTER_NAME="${1:-kvasir}"

KIND_CONFIG_FILE=$(mktemp)
trap 'rm -f $KIND_CONFIG_FILE' EXIT

# Write configuration to temp file
cat <<EOF >"$KIND_CONFIG_FILE"
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 32080
        hostPort: 80
        protocol: TCP
      - containerPort: 32443
        hostPort: 443
        protocol: TCP
  - role: worker
containerdConfigPatches:
  - |-
    [plugins."io.containerd.grpc.v1.cri".registry]
      config_path = "/etc/containerd/certs.d"
EOF

# Function to validate the existing cluster's configuration
check_configuration() {
  echo "Verifying existing cluster configuration..."

  # Check for the control-plane node label
  if ! kubectl get nodes -l ingress-ready=true | grep -q "control-plane"; then
    echo >&2 "Error: Control plane node missing 'ingress-ready=true' label."
    return 1
  fi

  # Retrieve control-plane container ID
  container_id=$(docker ps -q -f "name=${CLUSTER_NAME}-control-plane")
  if [[ -z "$container_id" ]]; then
    echo >&2 "Error: Control plane container not found."
    return 1
  fi

  # Validate port mappings (80->32080 and 443->32443)
  declare -A required_ports=([80]=32080 [443]=32443)
  for host_port in "${!required_ports[@]}"; do
    container_port="${required_ports[$host_port]}"
    actual_port=$(docker inspect "$container_id" |
      jq -r ".[0].NetworkSettings.Ports[\"${container_port}/tcp\"][0].HostPort")
    if [[ "$actual_port" != "$host_port" ]]; then
      echo >&2 "Error: Port $container_port not mapped to host port $host_port."
      return 1
    fi
  done

  # Check containerd configuration for the registry path
  if ! docker exec "$container_id" grep -q 'config_path = "/etc/containerd/certs.d"' \
    /etc/containerd/config.toml; then
    echo >&2 "Error: containerd config missing expected registry path."
    return 1
  fi

  echo "Existing cluster configuration is valid."
  return 0
}

# 1. Create registry container unless it already exists
reg_name='kind-registry'
reg_port='5001'
if [ "$(docker inspect -f '{{.State.Running}}' "${reg_name}" 2>/dev/null || true)" != 'true' ]; then
  docker run \
    -d --restart=always -p "127.0.0.1:${reg_port}:5000" --network bridge --name "${reg_name}" \
    registry:2
fi

# Check if the Kind cluster already exists
if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
  echo "Cluster already exists. Validating configuration..."

  # Export existing kubeconfig
  KUBECONFIG=$(mktemp)
  trap 'rm -f $KUBECONFIG' EXIT
  # set a temporary kubeconfig
  kind get kubeconfig -n "$CLUSTER_NAME" >"$KUBECONFIG"

  if check_configuration; then
    echo "Cluster is already configured correctly. Using existing cluster."
    exit 0
  else
    echo >&2 "Cluster exists but is misconfigured. Please delete it with: kind delete cluster --name=${CLUSTER_NAME}"
    exit 1
  fi
fi

# If we get here, create the cluster
echo "Creating cluster..."
if ! kind create cluster --name="$CLUSTER_NAME" --config="$KIND_CONFIG_FILE"; then
  echo >&2 "Cluster creation failed. This could be due to:"
  echo >&2 "1. Existing cluster with different configuration - try deleting it first"
  echo >&2 "2. Kind installation issues"
  echo >&2 "3. Docker not running - verify Docker daemon is active"
  exit 1
fi

echo "Cluster created successfully"

# 3. Add the registry config to the nodes
#
# This is necessary because localhost resolves to loopback addresses that are
# network-namespace local.
# In other words: localhost in the container is not localhost on the host.
#
# We want a consistent name that works from both ends, so we tell containerd to
# alias localhost:${reg_port} to the registry container when pulling images
REGISTRY_DIR="/etc/containerd/certs.d/localhost:${reg_port}"
for node in $(kind get nodes -n "$CLUSTER_NAME"); do
  docker exec "${node}" mkdir -p "${REGISTRY_DIR}"
  cat <<EOF | docker exec -i "${node}" cp /dev/stdin "${REGISTRY_DIR}/hosts.toml"
[host."http://${reg_name}:5000"]
EOF
done

# 4. Connect the registry to the cluster network if not already connected
# This allows kind to bootstrap the network but ensures they're on the same network
if [ "$(docker inspect -f='{{json .NetworkSettings.Networks.kind}}' "${reg_name}")" = 'null' ]; then
  docker network connect "kind" "${reg_name}"
fi

# 5. Document the local registry
# https://github.com/kubernetes/enhancements/tree/master/keps/sig-cluster-lifecycle/generic/1755-communicating-a-local-registry
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: local-registry-hosting
  namespace: kube-public
data:
  localRegistryHosting.v1: |
    host: "localhost:${reg_port}"
    help: "https://kind.sigs.k8s.io/docs/user/local-registry/"
EOF
