BIN     := bin/palmvellum
PKG     := github.com/palmvellum/palmvellum/packages/mac-daemon
VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo 'pre-alpha')

.PHONY: all build test lint vet run clean install

all: build

build:
	@mkdir -p bin
	@go build -o $(BIN) ./cmd/palmvellum
	@echo "built  $(BIN)  (version from cmd/palmvellum/main.go)"

test:
	@go test -race -count=1 ./...

lint vet:
	@go vet ./...

run: build
	@./$(BIN) serve

clean:
	@rm -rf bin

install: build
	@install -m 0755 $(BIN) ~/.local/bin/palmvellum
	@echo "installed to ~/.local/bin/palmvellum"
