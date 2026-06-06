#!/usr/bin/env bash
# scripts/bootstrap.sh — one-shot PalmVellum dev environment for macOS.
# Idempotent: safe to re-run. Skips anything already installed.
#
# What this sets up:
#   1. Homebrew (assumed already installed)
#   2. mise (Node + Go version manager)
#   3. Go 1.23+
#   4. Node 22 + pnpm 10
#
# After this runs, you can:
#   - Build the PWA:        pnpm --filter @palmvellum/pwa build
#   - Run the sync CLI:     cd packages/sync-cli && make && ./bin/vellum-sync
#   - Run the Mac daemon:   cd packages/mac-daemon && make && ./bin/palmvellum serve
#   - Test in an emulator:  open https://app.cloudpilot-emu.github.io/

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

bold "==> Bootstrap complete"
cat <<MSG

Next steps:
  1. Build the PWA:
       pnpm --filter @palmvellum/pwa build
  2. Build + run the Go sync CLI:
       cd packages/sync-cli && make && ./bin/vellum-sync --help
  3. Build + run the Mac daemon:
       cd packages/mac-daemon && make && ./bin/palmvellum doctor
  4. Bookmark CloudpilotEmu for emulator testing:
       https://app.cloudpilot-emu.github.io/

MSG
