BIN_DIR := bin
APP     := vellum-sync

all: $(BIN_DIR)/$(APP)

$(BIN_DIR)/$(APP): $(shell find . -name '*.go') go.mod
	mkdir -p $(BIN_DIR)
	go build -trimpath -o $(BIN_DIR)/$(APP) ./cmd/$(APP)

clean:
	rm -rf $(BIN_DIR)

.PHONY: all clean
