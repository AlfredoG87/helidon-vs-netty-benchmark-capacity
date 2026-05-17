#!/usr/bin/env bash
# End-to-end benchmark runner for the Kind cluster.
#
# Usage:
#   bash k8s/run-benchmark.sh              # baseline throughput matrix
#   bash k8s/run-benchmark.sh --chaos      # chaos scenario matrix (5 profiles × 60 combos)
#   bash k8s/run-benchmark.sh --chaos --no-teardown   # keep cluster alive after run
#
# Chaos tuning (pass extra --test-args after the flags):
#   bash k8s/run-benchmark.sh --chaos --test-args "-Dchaos.messages=100 -Dchaos.payloads=50,500"
#
# Requirements: kind, kubectl, docker, java 25, helm (optional)
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="0.0.1-SNAPSHOT"
CLUSTER_NAME="benchmark"

# ── Docker socket ──────────────────────────────────────────────────────────────
# Docker Desktop on macOS exposes a broken stub at /var/run/docker.sock.
# Use the raw socket if no DOCKER_HOST is already set.
if [[ -z "${DOCKER_HOST:-}" ]]; then
  RAW_SOCK="${HOME}/Library/Containers/com.docker.docker/Data/docker.raw.sock"
  if [[ -S "${RAW_SOCK}" ]]; then
    export DOCKER_HOST="unix://${RAW_SOCK}"
    echo "    (using DOCKER_HOST=${DOCKER_HOST})"
  fi
fi

# ── Argument parsing ───────────────────────────────────────────────────────────
RUN_CHAOS=false
TEARDOWN=true
EXTRA_TEST_ARGS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --chaos)        RUN_CHAOS=true ; shift ;;
    --no-teardown)  TEARDOWN=false ; shift ;;
    --test-args)    EXTRA_TEST_ARGS="$2" ; shift 2 ;;
    *) echo "Unknown flag: $1" ; exit 1 ;;
  esac
done

cd "$ROOT_DIR"

# ── 1. Build ───────────────────────────────────────────────────────────────────
echo "==> Building distribution..."
./gradlew distTar

echo "==> Building Docker images (--no-cache)..."
docker build --no-cache \
  --build-arg VERSION="${VERSION}" --build-arg IMPLEMENTATION=netty --build-arg PORT=50051 \
  -t "helidon-vs-netty-benchmark:${VERSION}-netty" \
  -f docker/Dockerfile .
docker build --no-cache \
  --build-arg VERSION="${VERSION}" --build-arg IMPLEMENTATION=helidon --build-arg PORT=50052 \
  -t "helidon-vs-netty-benchmark:${VERSION}-helidon" \
  -f docker/Dockerfile .

# ── 2. Cluster ─────────────────────────────────────────────────────────────────
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
  echo "==> Cluster '${CLUSTER_NAME}' already exists — reusing it."
else
  echo "==> Creating Kind cluster '${CLUSTER_NAME}'..."
  kind create cluster --config k8s/kind-config.yaml
fi

# ── 3. Load images ─────────────────────────────────────────────────────────────
echo "==> Loading images into Kind..."
kind load docker-image "helidon-vs-netty-benchmark:${VERSION}-netty"   --name "$CLUSTER_NAME"
kind load docker-image "helidon-vs-netty-benchmark:${VERSION}-helidon" --name "$CLUSTER_NAME"

# ── 4. Run ─────────────────────────────────────────────────────────────────────
if [[ "${RUN_CHAOS}" == "true" ]]; then
  echo "==> Running chaos benchmark (5 profiles × 60 combinations)..."
  # shellcheck disable=SC2086
  ./gradlew test -PincludeChaos \
    --tests org.example.benchmark.KindChaosIntegrationTest \
    --rerun-tasks \
    ${EXTRA_TEST_ARGS}
else
  echo "==> Running baseline throughput matrix (16 combinations)..."
  ./gradlew test -PincludeKind \
    --tests org.example.benchmark.KindThroughputIntegrationTest \
    --rerun-tasks
fi

# ── 5. Tear down ───────────────────────────────────────────────────────────────
if [[ "${TEARDOWN}" == "true" ]]; then
  echo "==> Deleting Kind cluster..."
  kind delete cluster --name "$CLUSTER_NAME"
else
  echo "==> Cluster '${CLUSTER_NAME}' kept alive (--no-teardown)."
  echo "    Delete with: kind delete cluster --name ${CLUSTER_NAME}"
fi

echo "Done."
