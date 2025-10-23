#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <version> [implementation] [port]"
  echo "  version         Distribution version, e.g. 0.0.1-SNAPSHOT"
  echo "  implementation  netty | helidon (default: netty)"
  echo "  port            Port exposed by the server (default: 50051 for netty, 50052 for helidon)"
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$1"
IMPLEMENTATION="${2:-netty}"
DEFAULT_PORT=$([[ "${IMPLEMENTATION}" == "helidon" ]] && echo "50052" || echo "50051")
PORT="${3:-$DEFAULT_PORT}"
DIST_TAR="helidon-vs-netty-benchmark-capacity-${VERSION}.tar"
DIST_PATH="${ROOT_DIR}/build/distributions/${DIST_TAR}"

if [[ ! -f "${DIST_PATH}" ]]; then
  echo "Distribution archive ${DIST_TAR} not found in build/distributions."
  echo "Run './gradlew distTar' first."
  exit 1
fi

IMAGE="helidon-vs-netty-benchmark:${VERSION}-${IMPLEMENTATION}"

echo "Building image ${IMAGE} (implementation=${IMPLEMENTATION}, port=${PORT})"

docker buildx build \
  --load \
  --build-arg VERSION="${VERSION}" \
  --build-arg IMPLEMENTATION="${IMPLEMENTATION}" \
  --build-arg PORT="${PORT}" \
  -t "${IMAGE}" \
  -f "${ROOT_DIR}/docker/Dockerfile" \
  "${ROOT_DIR}"

echo "Image ${IMAGE} built successfully."
