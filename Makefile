.DEFAULT_GOAL := build

ZARF_VERSION := "latest"
ORAS_VERSION := "1.2.2"
ADDLICENSE_VERSION := "v1.1.1"
ARCH := $(shell go env GOARCH)
OS := $(shell go env GOOS)

.PHONY: build
build:
	@ ./gradlew build

.PHONY: test
test: registry-up registry-seed
	@ ./gradlew test --fail-fast

.PHONY: lint
lint:
	@ ./gradlew detekt

.PHONY: registry-up
registry-up:
	@ docker compose up -d

.PHONY: registry-down
registry-down:
	@ docker compose down

.PHONY: registry-reset
registry-reset: registry-down
	@ rm -r ./.registry/* 2>/dev/null || true
	@ $(MAKE) registry-up registry-seed

.PHONY: registry-seed
registry-seed: registry-up
	@ ./bin/zarf package publish oci://ghcr.io/zarf-dev/packages/dos-games:1.1.0 oci://localhost:5005 --plain-http -a amd64
	@ ./bin/zarf package publish oci://ghcr.io/zarf-dev/packages/dos-games:1.1.0 oci://localhost:5005 --plain-http -a arm64
	@ ./bin/oras cp docker.io/library/registry:2.8.0  localhost:5005/library/registry:2.8.0
	@ ./bin/oras cp docker.io/library/registry:latest localhost:5005/library/registry:latest

.PHONY: addlicense
addlicense:
	addlicense -l apache -s=only -v -c 'Defense Unicorns' src

.PHONY: install-deps
install-deps:
	@ZARF_VERSION=$(ZARF_VERSION) \
	ORAS_VERSION=$(ORAS_VERSION) \
	ADDLICENSE_VERSION=$(ADDLICENSE_VERSION) \
	ARCH=$(ARCH) \
	OS=$(OS) \
	./hack/install-deps.sh
