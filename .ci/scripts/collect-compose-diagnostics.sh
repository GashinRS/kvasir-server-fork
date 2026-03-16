#!/usr/bin/env bash
set +e  # Don't exit on errors - we want to collect as much info as possible

# Script to collect comprehensive Docker Compose diagnostics for CI debugging.
#
# Usage: collect-compose-diagnostics.sh [output-dir] [--file <compose-file>]
#
#   output-dir        Directory to write diagnostics to (default: compose-diagnostics)
#   --file <path>     Path to the compose file (optional; uses Docker Compose default
#                     discovery when omitted)
#
# Services are auto-discovered from the compose project via
# `docker compose config --services`. An explicit list is no longer needed.
#
# Examples:
#   collect-compose-diagnostics.sh compose-diagnostics --file compose/compose.devservices.yml
#   collect-compose-diagnostics.sh diagnostics
#   collect-compose-diagnostics.sh   # uses all defaults

OUTPUT_DIR="compose-diagnostics"
COMPOSE_FILE=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --file)
            COMPOSE_FILE="$2"
            shift 2
            ;;
        --*)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
        *)
            OUTPUT_DIR="$1"
            shift
            ;;
    esac
done

# Build compose command
if [ -n "$COMPOSE_FILE" ]; then
    COMPOSE_CMD="docker compose -f $COMPOSE_FILE"
else
    COMPOSE_CMD="docker compose"
fi

# Auto-discover services from the compose project
SERVICES=()
while IFS= read -r svc; do
    [ -n "$svc" ] && SERVICES+=("$svc")
done < <($COMPOSE_CMD config --services 2>/dev/null)

echo "=== Docker Compose Diagnostics Collection ==="
echo "Compose file: ${COMPOSE_FILE:-default}"
echo "Output directory: $OUTPUT_DIR"
echo "Services: ${SERVICES[*]:-none discovered}"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Collect container status
echo "=== Collecting container status ==="
docker ps -a > "$OUTPUT_DIR/docker-ps.txt" 2>&1 || true

echo "=== Collecting detailed container inspection ==="
$COMPOSE_CMD ps -a --format json > "$OUTPUT_DIR/compose-ps-detailed.json" 2>&1 || true

# Collect individual service logs
echo "=== Collecting individual service logs ==="
for service in "${SERVICES[@]}"; do
    echo "  - Collecting logs for $service..."
    $COMPOSE_CMD logs --no-color "$service" > "$OUTPUT_DIR/service-${service}.log" 2>&1 || true
done

# Collect all compose logs
echo "=== Collecting all compose logs ==="
$COMPOSE_CMD logs --no-color > "$OUTPUT_DIR/compose-all.log" 2>&1 || true

# Collect Kafka diagnostics (if kafka is in the service list)
if [[ " ${SERVICES[*]} " =~ " kafka " ]]; then
    echo "=== Collecting Kafka diagnostics ==="
    echo "=== Kafka Broker API Versions ===" > "$OUTPUT_DIR/kafka-broker-versions.txt"
    $COMPOSE_CMD exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 \
        >> "$OUTPUT_DIR/kafka-broker-versions.txt" 2>&1 || \
        echo "FAILED: Kafka broker API versions check" >> "$OUTPUT_DIR/kafka-broker-versions.txt"

    echo "=== Kafka Topics ===" > "$OUTPUT_DIR/kafka-topics.txt"
    $COMPOSE_CMD exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list \
        >> "$OUTPUT_DIR/kafka-topics.txt" 2>&1 || \
        echo "FAILED: Kafka topics list" >> "$OUTPUT_DIR/kafka-topics.txt"

    echo "=== Kafka Cluster Info ===" > "$OUTPUT_DIR/kafka-cluster-info.txt"
    $COMPOSE_CMD exec -T kafka /opt/kafka/bin/kafka-metadata.sh --bootstrap-server localhost:9092 describe --cluster \
        >> "$OUTPUT_DIR/kafka-cluster-info.txt" 2>&1 || \
        echo "FAILED: Kafka cluster info" >> "$OUTPUT_DIR/kafka-cluster-info.txt"

    echo "=== Kafka Consumer Groups ===" > "$OUTPUT_DIR/kafka-consumer-groups.txt"
    $COMPOSE_CMD exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list \
        >> "$OUTPUT_DIR/kafka-consumer-groups.txt" 2>&1 || \
        echo "FAILED: Kafka consumer groups list" >> "$OUTPUT_DIR/kafka-consumer-groups.txt"
fi

# Collect health checks for specific services
echo "=== Collecting service health checks ==="
if [[ " ${SERVICES[*]} " =~ " clickhouse " ]]; then
    $COMPOSE_CMD exec -T clickhouse wget --no-verbose --tries=1 --spider http://localhost:8123/ping > "$OUTPUT_DIR/clickhouse-health.txt" 2>&1 || true
fi

# SeaweedFS diagnostics
if [[ " ${SERVICES[*]} " =~ " seaweedfs " ]]; then
    echo "=== SeaweedFS Status ===" > "$OUTPUT_DIR/seaweedfs-status.txt"
    $COMPOSE_CMD exec -T seaweedfs wget -qO- http://localhost:8333/ >> "$OUTPUT_DIR/seaweedfs-status.txt" 2>&1 || \
        echo "FAILED: SeaweedFS status check failed" >> "$OUTPUT_DIR/seaweedfs-status.txt"

    echo -e "\n=== SeaweedFS Volume List ===" >> "$OUTPUT_DIR/seaweedfs-status.txt"
    $COMPOSE_CMD exec -T seaweedfs weed shell << 'EOF' >> "$OUTPUT_DIR/seaweedfs-status.txt" 2>&1 || true
volume.list
exit
EOF

    echo -e "\n=== SeaweedFS Cluster Info ===" >> "$OUTPUT_DIR/seaweedfs-status.txt"
    $COMPOSE_CMD exec -T seaweedfs weed shell << 'EOF' >> "$OUTPUT_DIR/seaweedfs-status.txt" 2>&1 || true
cluster.check
exit
EOF
fi

# Collect container stats
echo "=== Collecting container resource stats ==="
docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.NetIO}}\t{{.BlockIO}}" > "$OUTPUT_DIR/docker-stats.txt" 2>&1 || true

# Collect network information
echo "=== Collecting Docker network info ==="
docker network ls > "$OUTPUT_DIR/docker-networks.txt" 2>&1 || true

# Try to get network details - network name might vary
for network in $($COMPOSE_CMD config --format json 2>/dev/null | grep -o '"name":"[^"]*"' | cut -d'"' -f4 || echo ""); do
    if [ -n "$network" ]; then
        docker network inspect "$network" > "$OUTPUT_DIR/docker-network-${network}.json" 2>&1 || true
    fi
done

# Fallback: try common network names
for network_name in compose_default default; do
    if docker network inspect "$network_name" >/dev/null 2>&1; then
        docker network inspect "$network_name" > "$OUTPUT_DIR/docker-network-${network_name}.json" 2>&1 || true
    fi
done

echo ""
echo "=== Diagnostics collection complete ==="
echo "Output directory: $OUTPUT_DIR"
echo "Files collected:"
ls -lh "$OUTPUT_DIR/" 2>/dev/null || true
