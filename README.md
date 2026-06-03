# koci

> "kay oh see eye"

Kotlin implementation of the [OCI Distribution client specification](https://github.com/opencontainers/distribution-spec/blob/master/spec.md).

Grab from [GitHub packages](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry) or [Maven Central](https://central.sonatype.com/artifact/com.defenseunicorns/koci)

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

## Migrating from v1

v2 changes the entry point, error model, event types, and authentication API. The sections below cover every breaking change.

### Entry point

v1 constructed `Registry` directly and required an externally created `Layout`. v2 introduces `Koci` as the single entry point — it owns the HTTP engine and the on-disk OCI layout, and hands out `Registry` views:

```kotlin
// v1
val layout = Layout.create("/tmp/koci-store").getOrThrow()
val repo = Registry("https://ghcr.io").repo("linuxcontainers/alpine")
repo.pull(tag = "latest", store = layout).collect { percent -> println("$percent%") }

// v2
Koci(root = "/tmp/koci-store").use { koci ->
  val repo = koci.registry("https://ghcr.io").repo("linuxcontainers/alpine")
  repo.pull(tag = "latest").collect { event -> println(event) }
}
```

`Koci` implements `AutoCloseable`. Use `.use {}` for scoped lifetimes or let your DI container manage it for singletons.

### Imports

All public types moved from `com.defenseunicorns.koci` to `com.defenseunicorns.koci.api`:

```kotlin
// v1
import com.defenseunicorns.koci.Descriptor
import com.defenseunicorns.koci.Registry

// v2
import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Registry
```

### Authentication

`Credential` is removed. Pass an `AuthConfig` to `Koci.registry()` instead:

| v1 | v2 |
| --- | --- |
| Anonymous (bare `HttpClient`) | `AuthConfig.None` (default) |
| `Credential(username, password, "", "")` | `AuthConfig.Basic(user, pass)` |
| `Credential("", "", "", accessToken)` | `AuthConfig.Bearer(token)` |

```kotlin
// v1
val cred = Credential(user, pass, "", "")
val client = HttpClient(CIO) { install(OCIAuthPlugin) { credential = cred } }
val registry = Registry("https://ghcr.io", client)

// v2
val registry = koci.registry("https://ghcr.io", auth = AuthConfig.Basic(user, pass))
```

### Error handling

v1 returned `Result<T>` and threw `OCIException` subclasses on failure. v2 surfaces failures as `null` return values, `false` booleans, or `TransferEvent.Failed` — nothing escapes as an exception. Failure details are logged internally.

Remove all `try/catch OCIException` blocks and `.getOrThrow()` / `.getOrDefault()` calls.

### Pull and push events

`Flow<Int>` (pull) and `Flow<Long>` (push) are replaced by `Flow<TransferEvent>`:

```kotlin
sealed interface TransferEvent {
  data class Progress(val percent: Int) : TransferEvent  // 0–100
  data object Failed : TransferEvent                      // terminal failure
}
```

`Progress(100)` is the success terminal. `Failed` is the failure terminal. There is no separate `Completed` event.

```kotlin
// v1 — pull
repo.pull(tag = "latest", store = layout).collect { percent -> println("$percent%") }

// v2 — pull
repo.pull(tag = "latest").collect { event ->
  when (event) {
    is TransferEvent.Progress -> println("${event.percent}%")
    TransferEvent.Failed -> println("failed")
  }
}
```

```kotlin
// v1 — push
repo.push(stream, descriptor).collect { bytesUploaded -> /* ... */ }

// v2 — push
repo.push(stream, descriptor).collect { event ->
  when (event) {
    is TransferEvent.Progress -> println("${event.percent}%")
    TransferEvent.Failed -> println("failed")
  }
}
```

### Catalog

The `registry.extensions` inner class is removed. `catalog()` is a direct method on `Registry` and always returns `Flow<List<Repository>>`:

```kotlin
// v1 — single page
registry.extensions.catalog().getOrThrow().repositories.forEach { println(it) }

// v1 — paginated
registry.extensions.catalog(n = 100).collect { page -> page.repositories.forEach { println(it) } }

// v2 — always a flow, defaults to up to 1000 per page
registry.catalog().collect { page -> page.forEach { println(it.name) } }
registry.catalog(n = 100).collect { page -> page.forEach { println(it.name) } }
```

### `Descriptor.fromInputStream`

The parameter order changed — `stream` moved first:

```kotlin
// v1
Descriptor.fromInputStream(
  mediaType = "text/plain",
  algorithm = RegisteredAlgorithm.SHA256,
  stream = payload.inputStream(),
)

// v2
Descriptor.fromInputStream(
  stream = payload.inputStream(),
  mediaType = "text/plain",
)
```

`algorithm` defaults to `SHA256` in both versions.

### Method reference

| Method | v1 | v2 |
| --- | --- | --- |
| `Registry.ping` | `suspend (): Result<Boolean>` | `suspend (): Boolean` |
| `Registry.catalog` | `extensions.catalog(n): Flow<CatalogResponse>` | `catalog(n): Flow<List<Repository>>` |
| `Repository.exists` | `suspend (Descriptor): Result<Boolean>` | `suspend (Descriptor): Boolean` |
| `Repository.tags` | `suspend (): Result<TagsResponse>` | `suspend (): List<String>` |
| `Repository.resolve` | `suspend (tag, resolver?): Result<Descriptor>` | `suspend (tag, resolver?): Descriptor?` |
| `Repository.fetch` | `suspend <T>(Descriptor, handler): T` | `suspend <T>(Descriptor, handler): T?` |
| `Repository.pull` (tag) | `(tag, store, resolver?): Flow<Int>` | `(tag, resolver?): Flow<TransferEvent>` |
| `Repository.pull` (descriptor) | `(Descriptor, store): Flow<Int>` | `(Descriptor): Flow<TransferEvent>` |
| `Repository.push` (blob) | `(InputStream, Descriptor): Flow<Long>` | `(InputStream, Descriptor): Flow<TransferEvent>` |
| `Repository.push` (tree) | not available | `(Descriptor, tag?): Flow<TransferEvent>` |
| `Repository.tag` | `suspend (Versioned, ref): Result<Descriptor>` | `suspend (Manifest, ref): Descriptor?` / `(Index, ref): Descriptor?` |
| `Repository.remove` | `suspend (Descriptor): Result<Boolean>` | moved to `koci.layout.remove(Descriptor): Boolean` |

### Removed types

| Type | Notes |
| --- | --- |
| `Credential` | Replaced by `AuthConfig` |
| `OCIException` (and all subclasses) | Failures are logged; observe `TransferEvent.Failed` instead |
| `UploadStatus` | Internal |
| `CatalogResponse` | Pages are now `List<Repository>` |
| `TagsResponse` | Tags are now `List<String>` |
| `Versioned` | Removed; use `Manifest` or `Index` directly |
| `FailureResponse` / `ActionableFailure` / `ErrorCode` | Internal |

## Contributing

See [CONTRIBUTING.md](./.github/CONTRIBUTING.md).

## Code of Conduct

See [CODE_OF_CONDUCT.md](./.github/CODE_OF_CONDUCT.md).

## Special Thanks

- [Distribution Spec authors](https://github.com/opencontainers/distribution-spec)
- [`oras-go` authors](https://github.com/oras-project/oras-go)
- [`ktor` authors](https://github.com/ktorio/ktor)
