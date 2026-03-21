.PHONY: build clean install

VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
BUILD_TIME := $(shell date -u '+%Y-%m-%dT%H:%M:%SZ')

build:
	go build -ldflags="-s -w -X main.version=$(VERSION) -X main.buildTime=$(BUILD_TIME)" -o intellij-cli

clean:
	rm -f intellij-cli

install: build
	cp intellij-cli ~/bin/
