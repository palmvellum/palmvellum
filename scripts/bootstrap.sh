#!/usr/bin/env bash
# scripts/bootstrap.sh — one-shot Palm Vellum dev environment for macOS.
# Idempotent: safe to re-run. Skips anything already installed.
#
# What this sets up:
#   1. Homebrew (assumed already installed)
#   2. OrbStack (Docker daemon, lightweight on Mac)
#   3. mise (Node + Go version manager)
#   4. Go 1.23+
#   5. Node 22 + pnpm 10
#   6. jichu4n/palm-os Homebrew tap (pilot-link, prc-tools, palm-os-sdk, pilrc)
#      ⚠️ Requires up-to-date Xcode Command Line Tools (16.4+ on macOS 26)
#   7. palmvellum/palm-toolchain Docker image (Ubuntu 24.04 + prc-tools-remix)
#      → primary m68k compile path, no CLT required
#
# After this runs, you can:
#   - Build .prc files: ./scripts/palm-build.sh (uses Docker toolchain)
#   - Test in emulator: open https://app.cloudpilot-emu.github.io/ (web PWA)

set -euo pipefail

bold() { printf "\033[1m%s\033[0m\n" "$*"; }
ok()   { printf "  ✅ %s\n" "$*"; }
warn() { printf "  ⚠️  %s\n" "$*"; }
fail() { printf "  ❌ %s\n" "$*" >&2; }

# --- 1. Homebrew ---
bold "==> Checking Homebrew"
if ! command -v brew >/dev/null 2>&1; then
  fail "Homebrew missing. Install from https://brew.sh first."
  exit 1
fi
ok "$(brew --version | head -1)"

# --- 2-5. brew packages ---
bold "==> Installing OrbStack + mise + go"
brew install --quiet orbstack mise go 2>&1 | grep -v "already installed" || true
ok "$(/opt/homebrew/bin/mise --version)"
ok "$(/opt/homebrew/bin/go version)"

if [ -d /Applications/OrbStack.app ]; then
  ok "OrbStack.app installed"
else
  fail "OrbStack install failed"
  exit 1
fi

# --- mise activation in zsh ---
if ! grep -q "mise activate" "$HOME/.zshrc" 2>/dev/null; then
  bold "==> Adding mise activation to ~/.zshrc"
  {
    echo ""
    echo "# mise (added by palmvellum bootstrap)"
    echo 'eval "$(/opt/homebrew/bin/mise activate zsh)"'
  } >> "$HOME/.zshrc"
  ok "mise activation added"
fi

# --- OrbStack PATH ---
if ! grep -q "OrbStack.app/Contents/MacOS/xbin" "$HOME/.zshrc" 2>/dev/null; then
  bold "==> Adding OrbStack to PATH in ~/.zshrc"
  {
    echo ""
    echo "# OrbStack (added by palmvellum bootstrap)"
    echo 'export PATH="/Applications/OrbStack.app/Contents/MacOS/xbin:$PATH"'
  } >> "$HOME/.zshrc"
  ok "OrbStack PATH added"
fi

# Make available in this session
export PATH="/Applications/OrbStack.app/Contents/MacOS/xbin:$PATH"

# --- Node + pnpm ---
bold "==> Node.js + pnpm"
if command -v node >/dev/null 2>&1; then
  ok "node $(node --version)"
else
  warn "node missing; install via mise: mise use -g node@22"
fi

if ! command -v pnpm >/dev/null 2>&1; then
  bold "==> Installing pnpm via npm"
  npm install -g pnpm@10
fi
ok "pnpm $(pnpm --version)"

# --- 6. jichu4n/palm-os tap (best-effort; may fail on outdated CLT) ---
bold "==> Tapping jichu4n/palm-os (for native pilot-link)"
brew tap jichu4n/palm-os 2>&1 | tail -3
warn "Native palm-os formulae build from source and require Xcode CLT 16.4+."
warn "On macOS 26: System Settings → Software Update → install any pending CLT update."
warn "If brew install fails with 'Command Line Tools are too outdated', skip — the"
warn "Docker toolchain below covers compile + build. pilot-xfer is only needed for"
warn "physical HotSync with real hardware (Task #8 / #9 — when FTDI cable arrives)."

# --- 7. Docker toolchain image ---
bold "==> Building palmvellum/palm-toolchain Docker image"
if ! command -v docker >/dev/null 2>&1; then
  fail "docker not found. Open OrbStack.app and complete first-run setup, then re-run this script."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  fail "docker daemon not running. Launch OrbStack.app and wait for it to start."
  exit 1
fi

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if docker image inspect palmvellum/palm-toolchain:latest >/dev/null 2>&1; then
  ok "palmvellum/palm-toolchain:latest already built ($(docker images palmvellum/palm-toolchain:latest --format '{{.Size}}'))"
else
  docker build --platform=linux/amd64 \
      -f "${REPO_ROOT}/scripts/palm-toolchain.Dockerfile" \
      -t palmvellum/palm-toolchain:latest \
      "${REPO_ROOT}"
  ok "Built palmvellum/palm-toolchain:latest"
fi

# --- Sanity test: compile a 845-byte hello world ---
bold "==> Toolchain sanity test"
if [ -f "${REPO_ROOT}/packages/palm-app/src/hello.c" ]; then
  if "${REPO_ROOT}/scripts/palm-build.sh" >/dev/null 2>&1; then
    ok "compile + link + build-prc all work; HelloVellum.prc produced"
    ls -la "${REPO_ROOT}/packages/palm-app/HelloVellum.prc"
  else
    warn "compile sanity test failed; run ./scripts/palm-build.sh manually to see errors"
  fi
fi

bold "==> Bootstrap complete"
cat <<MSG

Next steps:
  1. open OrbStack.app if first run (Privacy & Security may prompt)
  2. Bookmark CloudpilotEmu in your browser:
       https://app.cloudpilot-emu.github.io/
     ("Add to Dock" via Safari or "Install" via Chrome to get a PWA icon)
  3. Build the Palm app any time:
       ./scripts/palm-build.sh
  4. The compiled .prc lives at:
       packages/palm-app/HelloVellum.prc

For the Xcode CLT issue (only needed for physical HotSync once FTDI arrives):
  System Settings → Software Update → install any CLT update
  Or download from https://developer.apple.com/download/all/ ("Command Line
  Tools for Xcode 26.3" or later).

MSG
