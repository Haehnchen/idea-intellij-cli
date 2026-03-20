.PHONY: build clean install

build:
	go build -ldflags="-s -w" -o intellij-cli

clean:
	rm -f intellij-cli

install: build
	cp intellij-cli ~/bin/
