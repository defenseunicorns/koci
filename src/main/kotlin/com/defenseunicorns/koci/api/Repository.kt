/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.AuthConfig
import com.defenseunicorns.koci.api.config.BackOffPolicy
import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.internal.ACTION_PULL
import com.defenseunicorns.koci.internal.ACTION_PUSH
import com.defenseunicorns.koci.internal.ExistenceCheck
import com.defenseunicorns.koci.internal.Layout
import com.defenseunicorns.koci.internal.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.internal.OciConstants.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.internal.PushEvent
import com.defenseunicorns.koci.internal.Router
import com.defenseunicorns.koci.internal.TagsResponse
import com.defenseunicorns.koci.internal.UploadStatus
import com.defenseunicorns.koci.internal.appendScopes
import com.defenseunicorns.koci.internal.failureResponseOrNull
import com.defenseunicorns.koci.internal.scopeRepository
import com.defenseunicorns.koci.internal.toUploadStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okio.source

/**
 * A reference to a single repository within a [Registry].
 *
 * Construct via [Registry.repo] — the constructor is internal. Dependencies are threaded in
 * explicitly (no parent [Registry] reference) so the repository can be unit-tested without standing
 * up a full registry graph.
 *
 * Shares the registry's (and transitively the [Koci]'s) HTTP client; becomes unusable once that
 * [Koci] has been closed.
 */
public class Repository
internal constructor(
  public val name: String,
  private val auth: AuthConfig,
  private val pull: PullConfig,
  private val push: PushConfig,
  private val backOffPolicy: BackOffPolicy,
  internal val router: Router,
  internal val client: HttpClient,
  internal val store: Layout,
  internal val json: Json,
) {

  @Volatile private var supportsRange: Boolean? = null

  /** Tracks in-progress blob uploads for resumable operations. */
  private val uploading = ConcurrentHashMap<Descriptor, UploadStatus>()

  /**
   * Checks if a blob or manifest exists in the repository.
   *
   * Uses HEAD request to verify content existence without transferring data. Routes to appropriate
   * endpoint based on media type (manifest or blob).
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#checking-if-content-exists-in-the-registry">OCI
   *   Distribution Spec: Checking if Content Exists</a>
   */
  public suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
        else -> router.blob(name, descriptor)
      }
    client
      .head(endpoint) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }
      .status
      .isSuccess()
  }

  /**
   * Lists all tags in the repository.
   *
   * On any failure (HTTP error, parse error, OCI spec error response) returns [emptyList];
   * IOExceptions propagate from the underlying client. A caller iterating `repo.tags().forEach {
   * ... }` handles success-empty and failure the same way.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Tags</a>
   *
   * TODO: MOBILE-215 Implement pagination support as described in the specification
   */
  public suspend fun tags(): List<String> {
    val res =
      client.get(router.tags(name)) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }
    if (res.failureResponseOrNull() != null) {
      // TODO: Log
      return emptyList()
    }
    return try {
      val tags = res.body<TagsResponse>()
      tags.tags
    } catch (_: kotlinx.serialization.SerializationException) {
      // TODO: Log
      emptyList()
    }
  }

  /**
   * Resolves a tag to a descriptor, with optional platform filtering for index manifests.
   *
   * First performs a HEAD request to determine content type, then handles accordingly:
   * - For regular manifests: Returns [ResolveOutcome.Resolved].
   * - For index manifests: Returns [ResolveOutcome.Resolved] with the full index descriptor, or
   *   [ResolveOutcome.PlatformNotFound] when `platformResolver` rejects every entry.
   * - For unknown content types: [ResolveOutcome.ManifestNotSupported].
   * - For OCI spec error responses: [ResolveOutcome.RegistryError].
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  public suspend fun resolve(
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): ResolveOutcome {
    val endpoint = router.manifest(name, tag)
    val response =
      client.head(endpoint) {
        accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
        accept(ContentType.parse(INDEX_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      }
    response.failureResponseOrNull()?.let {
      return ResolveOutcome.RegistryError(it)
    }

    return when (response.contentType()?.toString()) {
      INDEX_MEDIA_TYPE -> resolveIndex(endpoint, platformResolver)
      MANIFEST_MEDIA_TYPE -> resolveManifest(endpoint)
      else -> ResolveOutcome.ManifestNotSupported(endpoint, response.contentType())
    }
  }

  @OptIn(ExperimentalSerializationApi::class)
  private suspend fun resolveIndex(
    endpoint: io.ktor.http.Url,
    platformResolver: ((Platform) -> Boolean)?,
  ): ResolveOutcome =
    client
      .prepareGet(endpoint) {
        accept(ContentType.parse(INDEX_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      }
      .execute { res ->
        res.failureResponseOrNull()?.let {
          return@execute ResolveOutcome.RegistryError(it)
        }
        if (platformResolver == null) {
          ResolveOutcome.Resolved(
            Descriptor.fromInputStream(
              mediaType = INDEX_MEDIA_TYPE,
              stream = res.body() as InputStream,
            )
          )
        } else {
          val index = res.body<Index>()
          val selected =
            index.manifests.firstOrNull { desc ->
              desc.platform != null && platformResolver(desc.platform)
            }
          if (selected == null) ResolveOutcome.PlatformNotFound(index)
          else ResolveOutcome.Resolved(selected)
        }
      }

  private suspend fun resolveManifest(endpoint: io.ktor.http.Url): ResolveOutcome =
    client
      .prepareGet(endpoint) {
        accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      }
      .execute { res ->
        res.failureResponseOrNull()?.let {
          return@execute ResolveOutcome.RegistryError(it)
        }
        ResolveOutcome.Resolved(
          Descriptor.fromInputStream(
            mediaType = MANIFEST_MEDIA_TYPE,
            stream = res.body() as InputStream,
          )
        )
      }

  /**
   * Pulls an image by tag and stores it in the provided layout.
   *
   * Resolves tag to descriptor, then pulls manifest and all referenced blobs. Emits [PullEvent]
   * events — [PullEvent.Progress] while work is in flight and a single terminal variant
   * ([PullEvent.Completed], [PullEvent.PlatformNotFound], [PullEvent.ManifestNotSupported],
   * [PullEvent.DigestMismatch], [PullEvent.SizeMismatch], [PullEvent.Incomplete], or
   * [PullEvent.RegistryError]).
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  public fun pull(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Flow<PullEvent> =
    channelFlow {
      val descriptor =
        when (val r = resolve(tag, platformResolver)) {
          is ResolveOutcome.Resolved -> r.descriptor
          is ResolveOutcome.PlatformNotFound -> {
            send(PullEvent.PlatformNotFound(r.index))
            return@channelFlow
          }
          is ResolveOutcome.ManifestNotSupported -> {
            send(PullEvent.ManifestNotSupported(r.endpoint, r.mediaType))
            return@channelFlow
          }
          is ResolveOutcome.RegistryError -> {
            send(PullEvent.RegistryError(r.response))
            return@channelFlow
          }
        }

      var terminalBeforeSuccess = false
      pull(descriptor).collect { event ->
        if (event !is PullEvent.Progress && event !is PullEvent.Completed) {
          terminalBeforeSuccess = true
        }
        send(event)
      }

      if (terminalBeforeSuccess) return@channelFlow

      val ref = Reference(registry = router.base(), repository = name, reference = tag)
      when (store.exists(descriptor)) {
        is ExistenceCheck.Present -> {
          store.tag(descriptor, ref)
          send(PullEvent.Completed)
        }
        else -> send(PullEvent.Incomplete(ref))
      }
    }

  /**
   * Pulls content by descriptor and stores it in the provided layout.
   *
   * Handles different content types appropriately (manifests, indices, blobs). For manifests and
   * indices, pulls all referenced content recursively.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
  public fun pull(descriptor: Descriptor): Flow<PullEvent> = channelFlow {
    when (descriptor.mediaType) {
      INDEX_MEDIA_TYPE -> {
        if (store.exists(descriptor) is ExistenceCheck.Present) {
          send(PullEvent.Progress(PROGRESS_COMPLETE))
          send(PullEvent.Completed)
          return@channelFlow
        }
        val index = index(descriptor)
        if (index == null) {
          send(
            PullEvent.Incomplete(
              Reference(router.base(), name, descriptor.digest?.toString() ?: "")
            )
          )
          return@channelFlow
        }
        val manifestsToPull = index.manifests
        val total = manifestsToPull.size
        var completedPulls = 0
        for (manifestDescriptor in manifestsToPull) {
          var failed = false
          pull(manifestDescriptor).collect { manifestEvent ->
            when (manifestEvent) {
              is PullEvent.Progress -> {
                val currentManifestContribution =
                  manifestEvent.percent.toDouble() / PROGRESS_COMPLETE
                val overallProgress =
                  ((completedPulls + currentManifestContribution) / total * PROGRESS_COMPLETE)
                    .roundToInt()
                send(PullEvent.Progress(overallProgress))
              }
              is PullEvent.Completed -> {
                // inner completion; accumulate progress
              }
              else -> {
                send(manifestEvent)
                failed = true
              }
            }
          }
          if (failed) return@channelFlow
          completedPulls += 1
        }
        val copyTerminal = runCopy(descriptor)
        if (copyTerminal != null) {
          send(copyTerminal)
          return@channelFlow
        }
        send(PullEvent.Progress(PROGRESS_COMPLETE))
        send(PullEvent.Completed)
      }

      MANIFEST_MEDIA_TYPE -> {
        if (store.exists(descriptor) is ExistenceCheck.Present) {
          send(PullEvent.Progress(PROGRESS_COMPLETE))
          send(PullEvent.Completed)
          return@channelFlow
        }
        val manifest = manifest(descriptor)
        if (manifest == null) {
          send(
            PullEvent.Incomplete(
              Reference(router.base(), name, descriptor.digest?.toString() ?: "")
            )
          )
          return@channelFlow
        }
        val layersToFetch = manifest.layers.toMutableList() + manifest.config
        val total = layersToFetch.sumOf { it.size } + manifest.config.size + descriptor.size

        val acc = AtomicInteger(0)

        var layerFailed = false
        layersToFetch
          .asFlow()
          .map { layer ->
            flow {
              var failed: PullEvent? = null
              copy(layer).collect { event ->
                when (event) {
                  is PullEvent.Progress -> {
                    val curr = acc.addAndGet(event.percent)
                    emit(
                      PullEvent.Progress((curr.toDouble() * PROGRESS_COMPLETE / total).roundToInt())
                    )
                  }
                  is PullEvent.Completed -> {}
                  else -> failed = event
                }
              }
              failed?.let { emit(it) }
            }
          }
          // TODO: figure out best API to expose concurrency settings
          .flattenMerge(concurrency = DEFAULT_LAYER_CONCURRENCY)
          .collect { event ->
            if (!layerFailed) {
              send(event)
              if (event !is PullEvent.Progress && event !is PullEvent.Completed) {
                layerFailed = true
              }
            }
          }

        if (layerFailed) return@channelFlow

        val manifestTerminal = runCopy(descriptor, total, acc)
        if (manifestTerminal != null) {
          send(manifestTerminal)
          return@channelFlow
        }
        send(PullEvent.Progress(PROGRESS_COMPLETE))
        send(PullEvent.Completed)
      }

      else -> {
        copy(descriptor).collect { event -> send(event) }
      }
    }
  }

  /**
   * Runs [copy] on a manifest/index descriptor and returns the first non-progress event (a terminal
   * failure) if any; otherwise null if the copy completed successfully.
   */
  private suspend fun runCopy(
    descriptor: Descriptor,
    total: Long = descriptor.size,
    acc: AtomicInteger = AtomicInteger(0),
  ): PullEvent? {
    var terminal: PullEvent? = null
    copy(descriptor).collect { event ->
      when (event) {
        is PullEvent.Progress -> {
          val curr = acc.addAndGet(event.percent)
          if (total > 0) {
            // swallow — outer caller decides when to emit progress
            curr
          }
        }
        is PullEvent.Completed -> {}
        else -> terminal = event
      }
    }
    return terminal
  }

  /**
   * Fetches and deserializes an index manifest (multi-platform manifest).
   *
   * Returns null when [descriptor] is not an index or when the fetch/deserialization fails.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  public suspend fun index(descriptor: Descriptor): Index? {
    if (descriptor.mediaType != INDEX_MEDIA_TYPE) return null
    return try {
      fetch(descriptor, json::decodeFromStream)
    } catch (_: kotlinx.serialization.SerializationException) {
      null
    }
  }

  /**
   * Generic content fetcher with custom processing.
   *
   * Retrieves content and processes it with the provided handler function. Returns null if
   * [descriptor] has no digest (no URL can be built). Used internally by [manifest], [index], and
   * [pull] methods.
   *
   * @param descriptor Content descriptor with mediaType and digest
   * @param handler Function to process the input stream
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI
   *   Distribution Spec: Pulling Blobs</a>
   */
  public suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T? {
    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
        else -> router.blob(name, descriptor)
      } ?: return null
    return client
      .prepareGet(endpoint) {
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))

        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
        }
      }
      .execute { res ->
        if (res.failureResponseOrNull() != null) {
          // TODO: Log
          null
        } else {
          res.body<InputStream>().use { stream -> handler(stream) }
        }
      }
  }

  /**
   * Fetches and deserializes a manifest from the registry.
   *
   * Returns null when [descriptor] is not a manifest or when the fetch/deserialization fails.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  public suspend fun manifest(descriptor: Descriptor): Manifest? {
    if (descriptor.mediaType != MANIFEST_MEDIA_TYPE) return null
    return try {
      fetch(descriptor, json::decodeFromStream)
    } catch (_: kotlinx.serialization.SerializationException) {
      null
    }
  }

  /**
   * Copies a blob to the layout with progress reporting.
   *
   * Emits [PullEvent.Progress] per chunk and a single terminal event (Completed / SizeMismatch /
   * DigestMismatch / RegistryError / Incomplete). Resume logic is driven by [Layout.exists]'s
   * [ExistenceCheck] classification.
   */
  @Suppress("detekt:CyclomaticComplexMethod", "detekt:LongMethod")
  private fun copy(descriptor: Descriptor): Flow<PullEvent> = channelFlow {
    val state = store.exists(descriptor)

    if (state is ExistenceCheck.Present) {
      send(PullEvent.Progress(descriptor.size.toInt()))
      send(PullEvent.Completed)
      return@channelFlow
    }

    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
        else -> router.blob(name, descriptor)
      }
    if (endpoint == null) {
      send(
        PullEvent.Incomplete(Reference(router.base(), name, descriptor.digest?.toString() ?: ""))
      )
      return@channelFlow
    }

    client
      .prepareGet(endpoint) {
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))

        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
        }

        when (state) {
          is ExistenceCheck.PartialBySize -> {
            if (supportsRange(descriptor)) {
              val start = state.onDiskSize
              // fire partial progress has happened
              send(PullEvent.Progress(start.toInt()))
              headers.append("Range", "bytes=$start-${descriptor.size - 1}")
            } else {
              store.remove(descriptor)
            }
          }

          is ExistenceCheck.Corrupted -> {
            store.remove(descriptor)
          }

          ExistenceCheck.Absent,
          ExistenceCheck.Present -> {
            // Absent: nothing to do; Present handled above
          }
        }
      }
      .execute { response ->
        val failure = response.failureResponseOrNull()
        if (failure != null) {
          send(PullEvent.RegistryError(failure))
          return@execute
        }
        response.body<InputStream>().use { stream ->
          store.push(descriptor, stream.source()).collect { pushEvent ->
            when (pushEvent) {
              is PushEvent.Progress -> send(PullEvent.Progress(pushEvent.bytes))
              is PushEvent.Completed -> send(PullEvent.Completed)
              is PushEvent.DigestMismatch ->
                send(PullEvent.DigestMismatch(pushEvent.expected, pushEvent.actual))
              is PushEvent.SizeMismatch ->
                send(PullEvent.SizeMismatch(pushEvent.expected, pushEvent.actual))
            }
          }
        }
      }
  }

  /**
   * Starts or resumes a blob upload session.
   *
   * Initiates new upload or resumes existing one if previously interrupted. Implements the initial
   * phase of the blob upload process.
   *
   * @param descriptor Blob descriptor to upload
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#starting-an-upload">OCI
   *   Distribution Spec: Starting an Upload</a>
   */
  @Suppress("detekt:NestedBlockDepth", "detekt:ReturnCount", "detekt:ThrowsCount")
  private suspend fun startOrResumeUpload(descriptor: Descriptor): UploadStatus {
    return when (val prev = uploading[descriptor]) {
      null -> {
        val res =
          client.post(router.uploads(name)) {
            headers[HttpHeaders.ContentLength] = 0.toString()
            attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
          }
        if (res.status != HttpStatusCode.Accepted) {
          throw OCIException.UnexpectedStatus(HttpStatusCode.Accepted, res)
        }
        return res.headers.toUploadStatus()
      }

      else -> {
        if (prev.offset > 0) {
          try {
            client
              .get(router.parseUploadLocation(prev.location)) {
                attributes.appendScopes(scopeRepository(name, ACTION_PULL))
              }
              .also { res ->
                if (res.status != HttpStatusCode.NoContent) {
                  throw OCIException.UnexpectedStatus(HttpStatusCode.NoContent, res)
                }

                val curr = res.headers.toUploadStatus()
                if (curr.offset != prev.offset) {
                  prev.offset = curr.offset
                }
              }
          } catch (e: OCIException.FromResponse) {
            if (e.fr.status == HttpStatusCode.NotFound) {
              uploading.remove(descriptor)
              return startOrResumeUpload(descriptor)
            }
            throw e
          } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
              uploading.remove(descriptor)
              return startOrResumeUpload(descriptor)
            }
            throw e
          }
        }

        prev
      }
    }
  }

  /**
   * Pushes a blob to the repository with chunked and resumable uploads.
   *
   * Uses monolithic upload for small blobs and chunked uploads for large ones (>5MB). Supports
   * resuming interrupted uploads from the last successful byte offset. Verifies content integrity
   * through digest validation.
   *
   * @param stream Input stream containing blob data
   * @param expected Descriptor with expected size and digest
   * @throws OCIException.DigestMismatch if computed digest doesn't match expected
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs">OCI
   *   Distribution Spec: Pushing Blobs</a>
   */
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
  public fun push(stream: InputStream, expected: Descriptor): Flow<Long> =
    channelFlow {
        if (exists(expected).getOrDefault(false)) {
          send(expected.size)
          withContext(Dispatchers.IO) { stream.close() }
          return@channelFlow
        }

        val start = startOrResumeUpload(expected).also { uploading[expected] = it }

        if (start.minChunkSize == 0L) {
          start.minChunkSize = 5 * 1024 * 1024
        }

        when (val bytesLeft = expected.size - start.offset) {
          in 1..start.minChunkSize -> {
            client
              .put(start.location) {
                url { encodedParameters.append("digest", expected.digest.toString()) }
                headers { append(HttpHeaders.ContentLength, expected.size.toString()) }
                setBody(stream)
                attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
              }
              .also { res ->
                if (res.status != HttpStatusCode.Created) {
                  throw OCIException.UnexpectedStatus(HttpStatusCode.Created, res)
                }

                send(bytesLeft)
              }
          }

          else -> {
            var offset = start.offset
            stream.use { s ->
              if (offset > 0) withContext(Dispatchers.IO) { s.skipNBytes(offset + 1) }

              while (isActive) {
                val chunk = withContext(Dispatchers.IO) { s.readNBytes(start.minChunkSize.toInt()) }

                if (chunk.isEmpty()) {
                  break
                }

                val endRange = offset + chunk.size - 1
                val currentLocation =
                  checkNotNull(uploading[expected]?.location) {
                    "upload location unexpectedly null"
                  }

                client
                  .patch(router.parseUploadLocation(currentLocation)) {
                    setBody(chunk)
                    headers { append(HttpHeaders.ContentRange, "$offset-$endRange") }
                    attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                  }
                  .also { res ->
                    if (res.status != HttpStatusCode.Accepted) {
                      throw OCIException.UnexpectedStatus(HttpStatusCode.Accepted, res)
                    }

                    val status = res.headers.toUploadStatus()
                    uploading[expected] = status
                    offset = status.offset + 1

                    send(offset)

                    yield()
                  }
              }
            }

            val final =
              checkNotNull(uploading[expected]?.location) { "upload location unexpectedly null" }

            client
              .put(final) {
                url { encodedParameters.append("digest", expected.digest.toString()) }
                attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
              }
              .also { res ->
                if (res.status != HttpStatusCode.Created) {
                  throw OCIException.UnexpectedStatus(HttpStatusCode.Created, res)
                }
              }
          }
        }
      }
      .onCompletion { cause -> if (cause == null) uploading.remove(expected) }

  /**
   * This endpoint may also support RFC7233 compliant range requests. Support can be detected by
   * issuing a HEAD request. If the header `Accept-Ranges: bytes` is returned, range requests can be
   * used to fetch partial content.
   */
  private suspend fun supportsRange(descriptor: Descriptor): Boolean {
    supportsRange?.let {
      return it
    }

    val endpoint = router.blob(name, descriptor) ?: return false

    val response =
      try {
        client.head(endpoint) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }
      } catch (_: Exception) {
        null
      }
    val rangeSupported = response?.headers?.get("Accept-Ranges") == "bytes"

    return synchronized(this) {
      supportsRange
        ?: run {
          supportsRange = rangeSupported
          rangeSupported
        }
    }
  }

  override fun toString(): String = "Repository(name=$name)"

  private companion object {
    private const val PROGRESS_COMPLETE = 100
    private const val DEFAULT_LAYER_CONCURRENCY = 6
  }
}
