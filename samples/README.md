# koci samples

## PullSample

Pulls an image by tag from an anonymous registry into a local OCI layout.

```kotlin
repo.pull(tag = "latest").collect { event ->
  when (event) {
    is PullEvent.Progress -> println("progress: ${event.percent}%")
    PullEvent.Completed -> println("done")
    PullEvent.Failed -> println("failed (cause logged inside koci)")
  }
}
```

If you already have a `Descriptor`, the descriptor overload skips the tag lookup:

```kotlin
val descriptor = repo.resolve("latest") ?: return
repo.pull(descriptor).collect { /* ... */ }
```

## PullByPlatformSample

Pulls a single platform variant out of a multi-arch index. The first manifest whose `Platform` matches is fetched; no match emits `PullEvent.Failed`.

```kotlin
repo.pull(
  tag = "latest",
  platformResolver = { it.os == "linux" && it.architecture == "arm64" },
).collect { /* ... */ }
```

Without `platformResolver`, koci walks the index and pulls every manifest.

## PushBlobSample

Pushes an arbitrary blob. `Descriptor.fromInputStream` streams the bytes through a hasher to compute size + sha256:

```kotlin
val descriptor = Descriptor.fromInputStream(
  stream = payload.inputStream(),
  mediaType = "text/plain",
)

repo.push(stream = payload.inputStream(), expected = descriptor).collect { /* ... */ }
```

If you already know the digest and size, build the `Descriptor` directly and skip the pre-pass:

```kotlin
val descriptor = Descriptor(
  mediaType = "text/plain",
  digest = Digest.parse("sha256:..."),
  size = payload.size.toLong(),
)
```

`push` is resumable — re-invoking with the same `expected` resumes the prior session.

## CatalogSample

One-shot listing:

```kotlin
for (repo in registry.catalog()) {
  println("${repo.name}: ${repo.tags()}")
}
```

Paginated via `Link` headers:

```kotlin
registry.catalog(n = 100).collect { page ->
  for (repo in page) println(repo.name)
}
```

Both forms (and `Repository.tags()`) return empty on transport / HTTP / decode failure.

## AuthSample

Prompts for a registry URL, username, and password, then pings with `AuthConfig.Basic`. Auth is registry-scoped — installed once on the shared HTTP client and inherited by every `Repository`.

```kotlin
// Anonymous (default)
koci.registry(url = "https://ghcr.io")

// HTTP Basic
koci.registry(url = url, auth = AuthConfig.Basic(user, pass))

// Pre-acquired bearer token
koci.registry(url = url, auth = AuthConfig.Bearer(token))
```
