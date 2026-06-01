# Palm Vellum cross-compile toolchain
# Builds m68k-palmos-gcc + pilrc + build-prc + Palm OS SDK
# on a stable Ubuntu base so we don't depend on macOS CLT version.
#
# Usage from repo root:
#   docker build -f scripts/palm-toolchain.Dockerfile -t palmvellum/palm-toolchain:latest .
#   docker run --rm -v "$PWD/packages/palm-app":/work -w /work \
#       palmvellum/palm-toolchain:latest \
#       sh -c "m68k-palmos-gcc -palmos3.1 -c src/hello.c"
#
FROM --platform=linux/amd64 ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl ca-certificates xz-utils \
        build-essential autoconf automake \
        gcc g++ make \
        bison flex texinfo \
        zlib1g-dev libreadline-dev libncurses-dev \
        zip unzip \
        git \
    && rm -rf /var/lib/apt/lists/*

# Install prc-tools-remix from official .deb (noble = Ubuntu 24.04)
# Pinned to a known-good release tag.
ARG PRC_TOOLS_VERSION=2.3-32
ARG PALM_OS_SDK_VERSION=5.0.3
ARG PILRC_VERSION=3.4

# Install prc-tools-remix + Palm OS SDK + PilRC, calling the 3 install
# scripts directly to skip the interactive wrapper's "Press Enter" prompt.
RUN set -eux \
    && cd /tmp \
    && curl -fsSL https://github.com/jichu4n/prc-tools-remix/raw/master/tools/install-prc-tools-remix.sh -o install-prc-tools-remix.sh \
    && curl -fsSL https://github.com/jichu4n/prc-tools-remix/raw/master/tools/setup-palm-os-sdk.sh -o setup-palm-os-sdk.sh \
    && curl -fsSL https://github.com/jichu4n/prc-tools-remix/raw/master/tools/install-pilrc.sh -o install-pilrc.sh \
    && chmod +x *.sh \
    && SUDO= bash install-prc-tools-remix.sh noble \
    && SUDO= bash setup-palm-os-sdk.sh \
    && SUDO= bash install-pilrc.sh noble \
    && rm -f *.sh

# Register all installed Palm OS SDKs with prc-tools (the upstream installer's
# `palmdev-prep -d sdk-5r3` only sets the default, doesn't actually register
# them on noble, so we run palmdev-prep again to scan /opt/palmdev fully).
RUN palmdev-prep -v 2>&1 | tail -30 \
 && palmdev-prep -d sdk-3.5

# Verify toolchain end-to-end with a minimal compile under SDK 3.1 baseline
# (matches our cross-target Palm OS 3.1 floor).
RUN m68k-palmos-gcc --version | head -1 \
 && pilrc -? 2>&1 | head -1 || true \
 && build-prc --version 2>&1 | head -1 || true \
 && echo "int main(void){return 0;}" > /tmp/t.c \
 && m68k-palmos-gcc -palmos3.1 -c /tmp/t.c -o /tmp/t.o \
 && echo "SDK 3.1 compile: OK"

WORKDIR /work

CMD ["bash"]
