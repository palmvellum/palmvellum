#!/usr/bin/env bash
# scripts/palm-build.sh - invoke the palm-toolchain Docker image on the current
# packages/palm-app/ directory. Usage:
#
#     ./scripts/palm-build.sh                       # default: make all
#     ./scripts/palm-build.sh make hello.prc        # custom make target
#     ./scripts/palm-build.sh m68k-palmos-gcc ...   # any command
#
# The Docker image carries m68k-palmos-gcc, pilrc, build-prc, and the Palm OS
# SDK headers. We mount packages/palm-app/ as /work inside the container.

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="${REPO_ROOT}/packages/palm-app"
IMAGE="palmvellum/palm-toolchain:latest"
PLATFORM="--platform=linux/amd64"

# Ensure OrbStack docker is on PATH
if ! command -v docker >/dev/null 2>&1; then
  if [[ -x /Applications/OrbStack.app/Contents/MacOS/xbin/docker ]]; then
    export PATH="/Applications/OrbStack.app/Contents/MacOS/xbin:${PATH}"
  else
    echo "error: docker not found and OrbStack not installed" >&2
    exit 1
  fi
fi

# Pull or build the image if missing
if ! docker image inspect "${IMAGE}" >/dev/null 2>&1; then
  echo "info: image ${IMAGE} not found locally; building from scripts/palm-toolchain.Dockerfile"
  docker build ${PLATFORM} \
      -f "${REPO_ROOT}/scripts/palm-toolchain.Dockerfile" \
      -t "${IMAGE}" \
      "${REPO_ROOT}"
fi

mkdir -p "${APP_DIR}"

# Run the requested command (default = make all)
if [[ $# -eq 0 ]]; then
  set -- make all
fi

exec docker run --rm ${PLATFORM} \
    -v "${APP_DIR}":/work \
    -w /work \
    "${IMAGE}" \
    "$@"
