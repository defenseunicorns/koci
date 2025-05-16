# koci

Kotlin implementation of the [OCI Distribution client specification](https://github.com/opencontainers/distribution-spec/blob/master/spec.md).

## Basic Usage

```kotlin
// 0. Create a file store
val store = runBlocking { Layout.create("/tmp/koci-store") }.getOrThrow()

// 1. Connect to a remote repository
val repo = Registry("https://public.ecr.aws").repo("ubuntu/redis")

// 2. Copy from the remote repository to the file store
val tag = "latest"

repo.pull(tag, store).collect{ prog ->
    println("$prog% done")
}
```

## Auth

- [x] [Request scopes](https://distribution.github.io/distribution/spec/auth/scope/) [relevant code](https://github.com/distribution/distribution/blob/v2.7.1/registry/handlers/app.go#L915-L937)
- [x] Basic/Bearer auth w/ Distribution auth flow
- [ ] Docker's `~/.docker/config.json` support

## Pull

> [Distribution specification](https://github.com/opencontainers/distribution-spec)

- [x] [GET `/v2/`](https://distribution.github.io/distribution/spec/api/#api-version-check) Ping registry
- [x] [GET `/v2/_catalog`](https://distribution.github.io/distribution/spec/api/#listing-repositories) Catalog
  - [x] Support pagination
- [x] [HEAD|GET `/v2/<name>/manifests/<reference>`](https://distribution.github.io/distribution/spec/api/#existing-manifests) Check for existence / fetch manifest by tag/digest
  - [x] Resolve/fetch tag into manifest/index
    - [x] Support custom index -> manifest resolution logic
  - [x] [Reference](https://pkg.go.dev/github.com/distribution/reference) support and validation
- [x] [DELETE `/v2/<name>/manifests/<digest>`](https://distribution.github.io/distribution/spec/api/#deleting-a-layer)
- [x] [GET `/v2/<name>/tags/list`](https://distribution.github.io/distribution/spec/api/#listing-image-tags) List image tags
  - [ ] Support pagination
- [x] [Pull image](https://distribution.github.io/distribution/spec/api/#pulling-an-image) (3 layer concurrency) with %/100 progress
  - [x] By tag
  - [x] By descriptor
  - [x] Into OCI layout
- [x] Fetch blob
  - [x] `Accept-Ranges: bytes` detection + `Range` support
  - [x] Resumable downloads
  - [x] Content verification using size + digest
- [ ] Referrers API

## Push

> [Distribution specification](https://github.com/opencontainers/distribution-spec)

- [x] Push blobs
  - [x] Resumable uploads
  - [x] Single PUT request if content < `OCI-Chunk-Min-Length` or 5MB if unset. Chunked upload otherwise
  - [x] Cross-repository blob mounting
- [x] Push + tag manifests/indexes
- [ ] Referrers API

## Store

> [Layout specification](https://github.com/opencontainers/image-spec/blob/main/image-layout.md)

Support for SHA-256 and SHA-512 hashing algorithms.

- [x] `index.json`
  - [x] Resolve by image reference via [`org.opencontainers.image.ref.name`](https://github.com/opencontainers/image-spec/blob/main/annotations.md#pre-defined-annotation-keys)
  - [x] Resolve by digest
  - [x] Resolve through custom logic
- [x] [`oci-layout`](https://github.com/opencontainers/image-spec/blob/main/image-layout.md#oci-layout-file)
- [x] [`blobs` directory](https://github.com/opencontainers/image-spec/blob/main/image-layout.md#blobs)
  - [x] Remove blob by descriptor
  - [x] Remove image/artifact by digest/reference
  - [x] Garbage collection

## Concurrency

> Changes to the concurrency API of `koci` are currently subject to change at any time.
>
> For now, it is best to run operations that could interfere (images sharing layers) sequentially.

## Contributing

See [CONTRIBUTING.md](./.github/CONTRIBUTING.md).

## Code of Conduct

See [CODE_OF_CONDUCT.md](./.github/CODE_OF_CONDUCT.md).

## Special Thanks

- [Distribution Spec authors](https://github.com/opencontainers/distribution-spec)
- [`oras-go` authors](https://github.com/oras-project/oras-go)
- [`ktor` authors](https://github.com/ktorio/ktor)
