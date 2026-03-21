# IntelliJ Agent CLI - Build Configuration

.PHONY: all build-plugin build-cli clean test install

all: build-plugin build-cli

# Build the IntelliJ plugin
build-plugin:
	./gradlew buildPlugin

# Build the CLI tool
VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
BUILD_TIME := $(shell date -u '+%Y-%m-%dT%H:%M:%SZ')

build-cli:
	cd cli && go mod tidy && go build -ldflags="-s -w -X main.version=$(VERSION) -X main.buildTime=$(BUILD_TIME)" -o intellij-cli

# Build everything for release
release: build-plugin build-cli
	@echo "Build complete!"
	@echo "Plugin: build/distributions/*.zip"
	@echo "CLI: cli/intellij-cli"

# Run tests
test:
	./gradlew test

# Clean build artifacts
clean:
	./gradlew clean
	rm -f cli/intellij-cli

# Install CLI to ~/.local/bin
install: build-cli
	@mkdir -p $(HOME)/.local/bin
	cp cli/intellij-cli $(HOME)/.local/bin/
	@echo "Installed to $(HOME)/.local/bin/intellij-cli"
	@echo "Make sure $(HOME)/.local/bin is in your PATH"

# Development: run IDE with plugin
run-ide:
	./gradlew runIde

# Format code
format:
	./gradlew spotlessApply || ./gradlew ktlintFormat || echo "No formatter configured"
