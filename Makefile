# Palm Vellum — top-level orchestration
#
# Usage:
#   make all         — build everything
#   make palm        — build the Palm OS .prc via Docker toolchain
#   make daemon      — build the Mac daemon Go binary
#   make pwa         — build the SvelteKit PWA
#   make schema      — typecheck the shared schema package
#   make clean       — clean every package's build artifacts
#   make doctor      — verify dev environment is healthy

REPO_ROOT := $(shell pwd)

.PHONY: all palm daemon pwa schema clean doctor help bootstrap

help:
	@echo "Palm Vellum — top-level targets:"
	@echo "  make all         build palm + daemon + pwa + schema"
	@echo "  make palm        build packages/palm-app/HelloVellum.prc"
	@echo "  make daemon      build packages/mac-daemon/bin/palmvellum"
	@echo "  make pwa         build packages/pwa/dist/"
	@echo "  make schema      typecheck packages/shared-schema"
	@echo "  make clean       clean every package"
	@echo "  make doctor      verify dev environment"
	@echo "  make bootstrap   run scripts/bootstrap.sh"

all: schema palm daemon pwa

palm:
	@./scripts/palm-build.sh

daemon:
	@if [ -f packages/mac-daemon/go.mod ]; then \
		cd packages/mac-daemon && go build -o bin/palmvellum ./cmd/palmvellum; \
	else \
		echo "  (mac-daemon scaffold pending — see issue #5)"; \
	fi

pwa:
	@if [ -f packages/pwa/package.json ]; then \
		pnpm --filter ./packages/pwa build; \
	else \
		echo "  (pwa scaffold pending — see issue #6)"; \
	fi

schema:
	@if [ -f packages/shared-schema/package.json ]; then \
		pnpm --filter ./packages/shared-schema typecheck; \
	else \
		echo "  (shared-schema scaffold pending)"; \
	fi

clean:
	@./scripts/palm-build.sh make clean 2>/dev/null || true
	@rm -rf packages/mac-daemon/bin packages/pwa/dist packages/shared-schema/dist
	@find . -type d -name node_modules -prune -exec rm -rf {} + 2>/dev/null || true

doctor:
	@echo "==> doctor"
	@command -v docker >/dev/null 2>&1 && docker version --format '  ✅ docker {{.Server.Version}}' || echo '  ❌ docker missing'
	@command -v node >/dev/null 2>&1 && echo "  ✅ node $$(node --version)" || echo '  ❌ node missing'
	@command -v pnpm >/dev/null 2>&1 && echo "  ✅ pnpm $$(pnpm --version)" || echo '  ❌ pnpm missing'
	@command -v go >/dev/null 2>&1 && echo "  ✅ $$(go version | cut -d' ' -f1-3)" || echo '  ❌ go missing'
	@docker image inspect palmvellum/palm-toolchain:latest >/dev/null 2>&1 && echo '  ✅ palm-toolchain image present' || echo '  ⚠️  palm-toolchain image missing (run `./scripts/bootstrap.sh`)'

bootstrap:
	@./scripts/bootstrap.sh
