# koci

Kotlin implementation of the [OCI Distribution client specification](https://github.com/opencontainers/distribution-spec/blob/master/spec.md).

## Auth

> Currently, authorization is configured via the provided [Ktor client](https://ktor.io/docs/client-auth.html)

- [ ] [Request scopes](https://distribution.github.io/distribution/spec/auth/scope/) https://github.com/distribution/distribution/blob/v2.7.1/registry/handlers/app.go#L921-L930
- [ ] Docker's `~/.docker/config.json` support

## Pull

> [Distribution specification](https://github.com/opencontainers/distribution-spec)

- [x] [GET `/v2/`](https://distribution.github.io/distribution/spec/api/#api-version-check) Ping registry
- [x] [GET `/v2/_catalog`](https://distribution.github.io/distribution/spec/api/#listing-repositories) Catalog w/ pagination support
- [x] [HEAD|GET `/v2/<name>/manifests/<reference>`](https://distribution.github.io/distribution/spec/api/#existing-manifests) Check for existence / fetch manifest by tag or digest
  - [ ] Support transforming Docker V2 manifest into OCI manifest if referenced by tag?
  - [x] Resolve/fetch tag into manifest || index
    - [x] Support custom manifest from index resolution logic
  - [~] [Reference](https://pkg.go.dev/github.com/distribution/reference) support and validation
- [x] DELETE `/v2/<name>/manifests/<digest>`
- [x] [GET `/v2/<name>/tags/list`](https://distribution.github.io/distribution/spec/api/#listing-image-tags) List image tags
  - [ ] Support pagination
- [x] [Pull image]((https://distribution.github.io/distribution/spec/api/#pulling-an-image)) (3 layer concurrency) with %/100 progress
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
- [x] Push + tag manifests || indexes
- [ ] Referrers API

## Store

> [Layout specification](https://github.com/opencontainers/image-spec/blob/main/image-layout.md)

Support for SHA-256 and SHA-512 hashing algorithms.

- [~] `index.json`
  - [~] Synchronized RW access (still some race conditions to sort through)
  - [x] Resolve by image reference via [`org.opencontainers.image.ref.name`](https://github.com/opencontainers/image-spec/blob/main/annotations.md#pre-defined-annotation-keys)
  - [x] Resolve by digest
  - [x] Resolve through custom logic
- [~] [`oci-layout`](https://github.com/opencontainers/image-spec/blob/main/image-layout.md#oci-layout-file)
- [x] [`blobs` directory](https://github.com/opencontainers/image-spec/blob/main/image-layout.md#blobs)
  - [x] Remove blob by descriptor
  - [x] Remove image by digest
  - [ ] Garbage collection strategy

## Concurrency

> Changes to the concurrency API of `koci` are currently subject to change at any time.
>
> For now, it is best to run operations that could interfere (images sharing layers) sequentially.

## Built using

- ktor
- kotlinx.serialization
- kotlinx.coroutines

- junit5
- kotlinx.kover
- kotlinx.test
