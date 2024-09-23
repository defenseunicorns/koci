.DEFAULT_GOAL := build

ZARF_VERSION := "latest"
ORAS_VERSION := "1.2.0"
ADDLICENSE_VERSION := "v1.1.1"
ARCH := $(shell go env GOARCH)
OS := $(shell go env GOOS)

.PHONY: build
build:
	@ ./gradlew build

test: registry-up registry-seed
	./gradlew test

lint:
	@ ./gradlew detekt

registry-up:
	@ docker compose up -d

registry-down:
	@ docker compose down

registry-reset: registry-down
	@rm -r ./.registry/* || true
	@$(MAKE) registry-up registry-seed

registry-seed:
	@./bin/zarf package publish oci://ghcr.io/zarf-dev/packages/dos-games:1.1.0 oci://localhost:5005 --insecure --oci-concurrency 5 --no-progress --no-log-file -a amd64
	@./bin/zarf package publish oci://ghcr.io/zarf-dev/packages/dos-games:1.1.0 oci://localhost:5005 --insecure --oci-concurrency 5 --no-progress --no-log-file -a arm64
	@./bin/oras cp docker.io/library/registry:2.8.0  localhost:5005/library/registry:2.8.0
	@./bin/oras cp docker.io/library/registry:latest localhost:5005/library/registry:latest

addlicense:
	addlicense -l apache -s=only -v -c 'Defense Unicorns' src

install-deps:
	@ZARF_VERSION=$(ZARF_VERSION) \
	ORAS_VERSION=$(ORAS_VERSION) \
	ADDLICENSE_VERSION=$(ADDLICENSE_VERSION) \
	ARCH=$(ARCH) \
	OS=$(OS) \
	./hack/install-deps.sh
