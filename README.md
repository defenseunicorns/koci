# koci

> "kay oh see eye"

Kotlin implementation of the [OCI Distribution client specification](https://github.com/opencontainers/distribution-spec/blob/master/spec.md).

Grab from [GitHub packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry) or [Maven Central](https://central.sonatype.com/artifact/com.defenseunicorns/koci)

## Usage

`Koci` is the entry point. It owns the HTTP engine and on-disk OCI layout, and hands out `Registry` and `Repository` views:

```kotlin
Koci(root = "/tmp/koci-store").use { koci ->
  val registry = koci.registry("https://ghcr.io")
  val repo = registry.repo("linuxcontainers/alpine")
  // ...
}
```

Each operation below has more than one shape, depending on what you already have on hand and how much control you want.

### Pulling

Pull an image by tag (resolves the tag, downloads the manifest and every referenced blob):

```kotlin
repo.pull(tag = "latest").collect { event ->
  when (event) {
    is PullEvent.Progress -> println("progress: ${event.percent}%")
    PullEvent.Completed -> println("done")
    PullEvent.Failed -> println("failed (cause logged inside koci)")
  }
}
```

Pull a single platform variant from a multi-arch index. The first manifest whose `Platform` matches is fetched; if nothing matches, `PullEvent.Failed` is emitted:

```kotlin
repo.pull(
  tag = "latest",
  platformResolver = { it.os == "linux" && it.architecture == "arm64" },
).collect { /* ... */ }
```

Without a `platformResolver`, koci walks an index and pulls every referenced manifest.

Pull by descriptor when you already have one (e.g. from `resolve(tag)` or a manifest you cached):

```kotlin
val descriptor = repo.resolve("latest") ?: return
repo.pull(descriptor).collect { /* ... */ }
```

The descriptor overload skips the tag lookup that the tag overload performs first.

### Pushing

Push a blob with a descriptor computed by streaming the payload through a hasher:

```kotlin
val descriptor = Descriptor.fromInputStream(
  stream = payload.inputStream(),
  mediaType = "text/plain",
)

repo.push(stream = payload.inputStream(), expected = descriptor).collect { /* ... */ }
```

If you already know the digest and size (e.g. from a manifest you're mirroring), construct the `Descriptor` directly and skip the streaming pre-pass:

```kotlin
val descriptor = Descriptor(
  mediaType = "text/plain",
  digest = Digest.parse("sha256:..."),
  size = payload.size.toLong(),
)
```

`push` is resumable. Re-invoking with the same `expected` descriptor picks up where the previous attempt left off.

### Listing

One-shot catalog returns every repository the registry advertises:

```kotlin
for (repo in registry.catalog()) {
  println("${repo.name}: ${repo.tags()}")
}
```

Paginated catalog follows `Link` headers and emits one page at a time, useful for very large registries:

```kotlin
registry.catalog(n = 100).collect { page ->
  for (repo in page) println(repo.name)
}
```

`Repository.tags()` lists tags for a single repository. Both `catalog()` and `tags()` return empty on transport, HTTP, or decode failure (cause logged inside the HTTP wrapper).

### Auth

Auth is registry-scoped. Once installed on a `Registry`, it applies to every `Repository` derived from it.

```kotlin
// Anonymous (default)
koci.registry(url = "https://ghcr.io")

// HTTP Basic
koci.registry(url = url, auth = AuthConfig.Basic(user, pass))

// Pre-acquired bearer token
koci.registry(url = url, auth = AuthConfig.Bearer(token))
```

Runnable demos for each of the above live in [`samples/`](./samples).

## Specification coverage

### Auth

- [x] [Request scopes](https://distribution.github.io/distribution/spec/auth/scope/) [relevant code](https://github.com/distribution/distribution/blob/v2.7.1/registry/handlers/app.go#L915-L937)
- [x] Basic/Bearer auth w/ Distribution auth flow
- [ ] Docker's `~/.docker/config.json` support

### Pull

> [Distribution specification](https://github.com/opencontainers/distribution-spec)

- [x] [GET `/v2/`](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support) Ping registry
- [x] [GET `/v2/_catalog`](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags) Catalog
  - [x] Support pagination
- [x] [HEAD|GET `/v2/<name>/manifests/<reference>`](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pull) Check for existence / fetch manifest by tag/digest
  - [x] Resolve/fetch tag into manifest/index
    - [x] Support custom index -> manifest resolution logic
  - [x] [Reference](https://pkg.go.dev/github.com/distribution/reference) support and validation
- [x] [DELETE `/v2/<name>/manifests/<digest>`](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-manifests)
- [x] [GET `/v2/<name>/tags/list`](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags) List image tags
  - [ ] Support pagination
- [x] [Pull image](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pull) (3 layer concurrency) with %/100 progress
  - [x] By tag
  - [x] By descriptor
  - [x] Into OCI layout
- [x] Fetch blob
  - [x] `Accept-Ranges: bytes` detection + `Range` support
  - [x] Resumable downloads
  - [x] Content verification using size + digest
- [ ] Referrers API

### Push

> [Distribution specification](https://github.com/opencontainers/distribution-spec)

- [x] Push blobs
  - [x] Resumable uploads
  - [x] Single PUT request if content < `OCI-Chunk-Min-Length` or 5MB if unset. Chunked upload otherwise
  - [x] Cross-repository blob mounting
- [x] Push + tag manifests/indexes
- [ ] Referrers API

### Store

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

## Contributing

See [CONTRIBUTING.md](./.github/CONTRIBUTING.md).

## Code of Conduct

See [CODE_OF_CONDUCT.md](./.github/CODE_OF_CONDUCT.md).

## Special Thanks

- [Distribution Spec authors](https://github.com/opencontainers/distribution-spec)
- [`oras-go` authors](https://github.com/oras-project/oras-go)
- [`ktor` authors](https://github.com/ktorio/ktor)
