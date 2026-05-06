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
import com.defenseunicorns.koci.internal.HttpWrapper
import com.defenseunicorns.koci.internal.Layout
import com.defenseunicorns.koci.internal.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.internal.OciConstants.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.internal.PushEvent
import com.defenseunicorns.koci.internal.Regex.tagRegex
import com.defenseunicorns.koci.internal.Router
import com.defenseunicorns.koci.internal.TagsResponse
import com.defenseunicorns.koci.internal.UploadStatus
import com.defenseunicorns.koci.internal.appendScopes
import com.defenseunicorns.koci.internal.scopeRepository
import com.defenseunicorns.koci.internal.toUploadStatus
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headers
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
import kotlinx.serialization.json.Json
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
  internal val caller: HttpWrapper,
  internal val store: Layout,
  internal val json: Json,
) {

  @Volatile private var supportsRange: Boolean? = null

  /** Tracks in-progress blob uploads for resumable operations. */
  private val uploading = ConcurrentHashMap<Descriptor, UploadStatus>()

  /**
   * Checks if a blob or manifest exists in the registry.
   *
   * Uses HEAD request to verify content existence without transferring data. Routes to the
   * appropriate endpoint based on media type. Returns false for 404 and any other non-success
   * status (404 is an expected "not found" outcome and is not logged); IOExceptions propagate from
   * the underlying client.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#checking-if-content-exists-in-the-registry">OCI
   *   Distribution Spec: Checking if Content Exists</a>
   */
  public suspend fun exists(descriptor: Descriptor): Boolean {
    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
        else -> router.blob(name, descriptor)
      } ?: return false
    val outcome =
      caller.call(
        operation = "repository.exists",
        buildRequest = {
          method = HttpMethod.Head
          url(endpoint)
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        },
        onSuccess = { true },
      )
    return outcome ?: false
  }

  /**
   * Lists all tags in the repository.
   *
   * On any failure (HTTP error, parse error, OCI spec error response) returns [emptyList] and logs
   * the failure internally.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Tags</a>
   *
   * TODO: #674 Implement pagination support as described in the specification
   */
  public suspend fun tags(): List<String> {
    val outcome =
      caller.call(
        operation = "repository.tags",
        buildRequest = {
          url(router.tags(name))
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        },
        onSuccess = { res -> res.body<TagsResponse>().tags },
      )
    return outcome ?: emptyList()
  }

  /**
   * Resolves a tag to a descriptor, with optional platform filtering for index manifests.
   *
   * Performs a HEAD request to determine content type, then handles accordingly:
   * - Regular manifest: returns the descriptor.
   * - Index manifest with no platform resolver: returns the index descriptor itself.
   * - Index manifest with a platform resolver: returns the matching platform's descriptor, or null
   *   if none matched.
   * - Anything else (unsupported content type, HTTP failure, OCI error response): returns null and
   *   logs the cause.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  public suspend fun resolve(
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Descriptor? {
    val endpoint = router.manifest(name, tag)
    val headOutcome =
      caller.call(
        operation = "repository.resolve.head",
        buildRequest = {
          method = HttpMethod.Head
          url(endpoint)
          accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          accept(ContentType.parse(INDEX_MEDIA_TYPE))
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        },
        onSuccess = { res -> res.contentType()?.toString() },
      )
    val contentType = headOutcome ?: return null

    return when (contentType) {
      INDEX_MEDIA_TYPE -> resolveIndex(endpoint, platformResolver)
      MANIFEST_MEDIA_TYPE -> resolveManifest(endpoint)
      else -> null
    }
  }

  private suspend fun resolveIndex(
    endpoint: Url,
    platformResolver: ((Platform) -> Boolean)?,
  ): Descriptor? =
    caller.call(
      operation = "repository.resolve.index",
      buildRequest = {
        url(endpoint)
        accept(ContentType.parse(INDEX_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      },
      onSuccess = { res ->
        when (platformResolver) {
          null ->
            Descriptor.fromInputStream(
              mediaType = INDEX_MEDIA_TYPE,
              stream = res.body() as InputStream,
            )
          else -> {
            val index = res.body<Index>()
            index.manifests.firstOrNull { desc ->
              desc.platform != null && platformResolver(desc.platform)
            }
          }
        }
      },
    )

  private suspend fun resolveManifest(endpoint: Url): Descriptor? =
    caller.call(
      operation = "repository.resolve.manifest",
      buildRequest = {
        url(endpoint)
        accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      },
      onSuccess = { res ->
        Descriptor.fromInputStream(
          mediaType = MANIFEST_MEDIA_TYPE,
          stream = res.body() as InputStream,
        )
      },
    )

  /**
   * Pulls an image by tag and stores it in the layout.
   *
   * Resolves the tag, then pulls manifest and all referenced blobs. Emits [PullEvent.Progress]
   * while bytes flow, then exactly one terminal event ([PullEvent.Completed] on success,
   * [PullEvent.Failed] otherwise). Specific failure causes are logged internally.
   */
  public fun pull(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Flow<PullEvent> =
    channelFlow {
      val descriptor = resolve(tag, platformResolver)
      if (descriptor == null) {
        send(PullEvent.Failed)
        return@channelFlow
      }

      var failed = false
      pull(descriptor).collect { event ->
        if (event is PullEvent.Failed) failed = true
        // Don't forward inner Completed; outer Flow emits its own after the tag write.
        if (event is PullEvent.Completed) return@collect
        send(event)
      }
      if (failed) return@channelFlow

      val ref = Reference(registry = router.base(), repository = name, reference = tag)
      if (store.exists(descriptor)) {
        store.tag(descriptor, ref)
        send(PullEvent.Completed)
      } else {
        // TODO: #658 - Log "post-pull verification failed for $ref"
        send(PullEvent.Failed)
      }
    }

  /**
   * Pulls content by descriptor and stores it in the layout.
   *
   * Handles different content types appropriately (manifests, indices, blobs). For manifests and
   * indices, pulls all referenced content recursively.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
  public fun pull(descriptor: Descriptor): Flow<PullEvent> = channelFlow {
    when (descriptor.mediaType) {
      INDEX_MEDIA_TYPE -> {
        if (store.exists(descriptor)) {
          send(PullEvent.Progress(PROGRESS_COMPLETE))
          send(PullEvent.Completed)
          return@channelFlow
        }
        val index = index(descriptor)
        if (index == null) {
          send(PullEvent.Failed)
          return@channelFlow
        }
        val total = index.manifests.size
        var completed = 0
        for (manifestDescriptor in index.manifests) {
          var innerFailed = false
          pull(manifestDescriptor).collect { event ->
            when (event) {
              is PullEvent.Progress -> {
                val frac = event.percent.toDouble() / PROGRESS_COMPLETE
                send(
                  PullEvent.Progress(((completed + frac) / total * PROGRESS_COMPLETE).roundToInt())
                )
              }
              is PullEvent.Completed -> {}
              is PullEvent.Failed -> innerFailed = true
            }
          }
          if (innerFailed) {
            send(PullEvent.Failed)
            return@channelFlow
          }
          completed += 1
        }
        val terminal = pipeCopy(descriptor)
        if (terminal != null) {
          send(terminal)
          return@channelFlow
        }
        send(PullEvent.Progress(PROGRESS_COMPLETE))
        send(PullEvent.Completed)
      }

      MANIFEST_MEDIA_TYPE -> {
        if (store.exists(descriptor)) {
          send(PullEvent.Progress(PROGRESS_COMPLETE))
          send(PullEvent.Completed)
          return@channelFlow
        }
        val manifest = manifest(descriptor)
        if (manifest == null) {
          send(PullEvent.Failed)
          return@channelFlow
        }
        val layersToFetch = manifest.layers.toMutableList() + manifest.config
        val total = layersToFetch.sumOf { it.size } + manifest.config.size + descriptor.size
        val acc = AtomicInteger(0)

        var layersFailed = false
        layersToFetch
          .asFlow()
          .map { layer ->
            flow {
              var localFailed: PullEvent? = null
              copy(layer).collect { event ->
                when (event) {
                  is PullEvent.Progress -> {
                    val curr = acc.addAndGet(event.percent)
                    emit(
                      PullEvent.Progress((curr.toDouble() * PROGRESS_COMPLETE / total).roundToInt())
                    )
                  }
                  is PullEvent.Completed -> {}
                  is PullEvent.Failed -> localFailed = PullEvent.Failed
                }
              }
              localFailed?.let { emit(it) }
            }
          }
          .flattenMerge(concurrency = DEFAULT_LAYER_CONCURRENCY)
          .collect { event ->
            if (!layersFailed) {
              send(event)
              if (event is PullEvent.Failed) layersFailed = true
            }
          }
        if (layersFailed) return@channelFlow

        val manifestTerminal = pipeCopy(descriptor)
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
   * Runs [copy] on a manifest/index descriptor and returns [PullEvent.Failed] if the copy fails;
   * null on success.
   */
  private suspend fun pipeCopy(descriptor: Descriptor): PullEvent? {
    var terminal: PullEvent? = null
    copy(descriptor).collect { event -> if (event is PullEvent.Failed) terminal = PullEvent.Failed }
    return terminal
  }

  /**
   * Fetches and deserializes an index manifest.
   *
   * Returns null when [descriptor] is not an index or when the fetch/deserialization fails. The
   * specific failure cause is logged.
   */
  public suspend fun index(descriptor: Descriptor): Index? {
    if (descriptor.mediaType != INDEX_MEDIA_TYPE) return null
    return requestJson(descriptor, INDEX_MEDIA_TYPE, "repository.index")
  }

  /**
   * Fetches and deserializes a manifest from the registry.
   *
   * Returns null when [descriptor] is not a manifest or when the fetch/deserialization fails.
   */
  public suspend fun manifest(descriptor: Descriptor): Manifest? {
    if (descriptor.mediaType != MANIFEST_MEDIA_TYPE) return null
    return requestJson(descriptor, MANIFEST_MEDIA_TYPE, "repository.manifest")
  }

  /**
   * Generic content fetcher with custom processing.
   *
   * Routes to the manifest or blob endpoint based on [descriptor]'s media type, GETs the content,
   * and hands the raw [InputStream] to [handler] for caller-defined processing. Returns null when
   * [descriptor] has no digest (no URL can be built) or when the request fails (cause logged).
   *
   * Escape hatch for consumers who need raw bytes — non-JSON blobs, custom hash-verifying stream
   * processing, streaming directly to disk, etc. For JSON-typed manifest/index access, prefer
   * [manifest] and [index].
   *
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
    return caller.call(
      operation = "repository.fetch",
      buildRequest = {
        url(endpoint)
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
        }
      },
      onSuccess = { res -> res.body<InputStream>().use { stream -> handler(stream) } },
    )
  }

  /**
   * GETs a manifest/index by descriptor and decodes the JSON body via ktor's ContentNegotiation
   * (configured permissively in [Koci]). Returns null on missing digest, non-success status, or
   * malformed JSON.
   */
  private suspend inline fun <reified T> requestJson(
    descriptor: Descriptor,
    mediaType: String,
    operation: String,
  ): T? {
    val endpoint = router.manifest(name, descriptor) ?: return null
    return caller.call(
      operation = operation,
      buildRequest = {
        url(endpoint)
        accept(ContentType.parse(mediaType))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      },
      onSuccess = { res -> res.body<T>() },
    )
  }

  /**
   * Copies a blob to the layout with progress reporting.
   *
   * Emits [PullEvent.Progress] per chunk and a single terminal event ([PullEvent.Completed] on
   * success, [PullEvent.Failed] otherwise). Range-resume of partial blobs is preserved via
   * [Layout.partialBytesOnDisk].
   */
  @Suppress("detekt:CyclomaticComplexMethod", "detekt:LongMethod")
  private fun copy(descriptor: Descriptor): Flow<PullEvent> = channelFlow {
    if (store.exists(descriptor)) {
      send(PullEvent.Progress(descriptor.size.toInt()))
      send(PullEvent.Completed)
      return@channelFlow
    }

    val resumeFrom = store.partialBytesOnDisk(descriptor)
    val resumeStart =
      when (resumeFrom != null && supportsRange(descriptor)) {
        true -> resumeFrom
        false -> null
      }
    when (resumeStart) {
      null -> store.remove(descriptor) // Clear corrupted / partial-without-range-support state.
      else -> send(PullEvent.Progress(resumeStart.toInt()))
    }

    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
        else -> router.blob(name, descriptor)
      }
    if (endpoint == null) {
      send(PullEvent.Failed)
      return@channelFlow
    }

    val outcome =
      caller.call(
        operation = "repository.copy",
        buildRequest = {
          url(endpoint)
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
          when (descriptor.mediaType) {
            MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
            INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
          }
          if (resumeStart != null) {
            headers.append("Range", "bytes=$resumeStart-${descriptor.size - 1}")
          }
        },
        onSuccess = { response ->
          response.body<InputStream>().use { stream ->
            store.push(descriptor, stream.source()).collect { pushEvent ->
              when (pushEvent) {
                is PushEvent.Progress -> send(PullEvent.Progress(pushEvent.bytes))
                is PushEvent.Completed -> send(PullEvent.Completed)
                is PushEvent.Failed -> send(PullEvent.Failed)
              }
            }
          }
        },
      )

    if (outcome == null) {
      send(PullEvent.Failed)
    }
  }

  /**
   * Starts or resumes a blob upload session.
   *
   * Initiates a new upload (POST /uploads/) or refreshes the offset of an existing upload session
   * (GET /uploads/<location>). Returns null on any unexpected status (logged). A 404 on the
   * existing session means it expired server-side; the cached entry is dropped and a new session is
   * started.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#starting-an-upload">OCI
   *   Distribution Spec: Starting an Upload</a>
   */
  @Suppress("detekt:ReturnCount")
  private suspend fun startOrResumeUpload(descriptor: Descriptor): UploadStatus? {
    val prev = uploading[descriptor]
    if (prev == null) {
      return caller.call(
        operation = "repository.upload.init",
        buildRequest = {
          method = HttpMethod.Post
          url(router.uploads(name))
          headers[HttpHeaders.ContentLength] = 0.toString()
          attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
        },
        onSuccess = { res ->
          when (res.status) {
            HttpStatusCode.Accepted -> res.headers.toUploadStatus()
            else -> null
          }
        },
      )
    }

    if (prev.offset > 0) {
      val outcome =
        caller.call(
          operation = "repository.upload.status",
          buildRequest = {
            url(router.parseUploadLocation(prev.location))
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
          },
          onError = { failure -> failure.status to null },
          onSuccess = { res -> res.status to res.headers.toUploadStatus() },
        )
      val resume = outcome ?: return null

      when (resume.first) {
        HttpStatusCode.NotFound -> {
          uploading.remove(descriptor)
          return startOrResumeUpload(descriptor)
        }
        HttpStatusCode.NoContent -> {
          val curr = resume.second ?: return null
          if (curr.offset != prev.offset) {
            prev.offset = curr.offset
          }
        }
        else -> return null
      }
    }

    return prev
  }

  /**
   * Pushes a blob to the registry with chunked and resumable uploads.
   *
   * Uses a monolithic upload for blobs that fit in a single chunk and a chunked
   * PATCH-then-final-PUT flow for larger blobs. Resumable across interruptions via the [uploading]
   * cache. Emits [PullEvent.Progress] while bytes flow and exactly one terminal event
   * ([PullEvent.Completed] on success, [PullEvent.Failed] otherwise — specific causes logged
   * internally).
   *
   * Reuses [PullEvent] today for shape consistency with [pull]; #670 will introduce a unified
   * progress type.
   *
   * @param stream Input stream containing blob data
   * @param expected Descriptor with expected size and digest
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs">OCI
   *   Distribution Spec: Pushing Blobs</a>
   */
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod", "detekt:ReturnCount")
  public fun push(stream: InputStream, expected: Descriptor): Flow<PullEvent> =
    channelFlow {
        if (exists(expected)) {
          send(PullEvent.Progress(PROGRESS_COMPLETE))
          send(PullEvent.Completed)
          withContext(Dispatchers.IO) { stream.close() }
          return@channelFlow
        }

        val start = startOrResumeUpload(expected)
        if (start == null) {
          send(PullEvent.Failed)
          return@channelFlow
        }
        uploading[expected] = start
        if (start.minChunkSize == 0L) {
          start.minChunkSize = DEFAULT_PUSH_CHUNK_SIZE
        }

        val total = expected.size

        when (val bytesLeft = total - start.offset) {
          in 1..start.minChunkSize -> {
            val outcome =
              caller.call(
                operation = "repository.push.monolithic",
                buildRequest = {
                  method = HttpMethod.Put
                  url(start.location)
                  url { encodedParameters.append("digest", expected.digest.toString()) }
                  headers { append(HttpHeaders.ContentLength, total.toString()) }
                  setBody(stream)
                  attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                },
                onSuccess = { res -> res.status },
              )
            if (outcome != HttpStatusCode.Created) {
              send(PullEvent.Failed)
              return@channelFlow
            }
            send(PullEvent.Progress(PROGRESS_COMPLETE))
            send(PullEvent.Completed)
          }

          else -> {
            var offset = start.offset
            stream.use { s ->
              if (offset > 0) withContext(Dispatchers.IO) { s.skipNBytes(offset + 1) }

              while (isActive) {
                val chunk = withContext(Dispatchers.IO) { s.readNBytes(start.minChunkSize.toInt()) }
                if (chunk.isEmpty()) break

                val endRange = offset + chunk.size - 1
                val currentLocation = uploading[expected]?.location
                if (currentLocation == null) {
                  // TODO: #658 - Log "upload session lost mid-push for $expected"
                  send(PullEvent.Failed)
                  return@channelFlow
                }

                val outcome =
                  caller.call(
                    operation = "repository.push.chunk",
                    buildRequest = {
                      method = HttpMethod.Patch
                      url(router.parseUploadLocation(currentLocation))
                      setBody(chunk)
                      headers { append(HttpHeaders.ContentRange, "$offset-$endRange") }
                      attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                    },
                    onSuccess = { res ->
                      when (res.status) {
                        HttpStatusCode.Accepted -> res.headers.toUploadStatus()
                        else -> null
                      }
                    },
                  )
                if (outcome == null) {
                  send(PullEvent.Failed)
                  return@channelFlow
                }
                uploading[expected] = outcome
                offset = outcome.offset + 1
                send(PullEvent.Progress(offset.toInt()))
                yield()
              }
            }

            val finalLocation = uploading[expected]?.location
            if (finalLocation == null) {
              // TODO: #658 - Log "upload session lost before commit for $expected"
              send(PullEvent.Failed)
              return@channelFlow
            }

            val outcome =
              caller.call(
                operation = "repository.push.commit",
                buildRequest = {
                  method = HttpMethod.Put
                  url(finalLocation)
                  url { encodedParameters.append("digest", expected.digest.toString()) }
                  attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                },
                onSuccess = { res -> res.status },
              )
            if (outcome != HttpStatusCode.Created) {
              send(PullEvent.Failed)
              return@channelFlow
            }
            send(PullEvent.Progress(PROGRESS_COMPLETE))
            send(PullEvent.Completed)
          }
        }
      }
      .onCompletion { cause -> if (cause == null) uploading.remove(expected) }

  /**
   * Tags a [Manifest] under [ref].
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests">OCI
   *   Distribution Spec: Pushing Manifests</a>
   */
  public suspend fun tag(content: Manifest, ref: String): Descriptor? =
    tag(ref, content.mediaType ?: MANIFEST_MEDIA_TYPE, json.encodeToString(content))

  /**
   * Tags an [Index] under [ref].
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests">OCI
   *   Distribution Spec: Pushing Manifests</a>
   */
  public suspend fun tag(content: Index, ref: String): Descriptor? =
    tag(ref, content.mediaType ?: INDEX_MEDIA_TYPE, json.encodeToString(content))

  private suspend inline fun tag(ref: String, mediaType: String, body: String): Descriptor? {
    if (tagRegex.matchEntire(ref) == null) return null
    return caller.call(
      operation = "repository.tag",
      buildRequest = {
        method = HttpMethod.Put
        url(router.manifest(name, ref))
        contentType(ContentType.parse(mediaType))
        setBody(body)
        attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
      },
      onSuccess = { res ->
        when (res.status) {
          HttpStatusCode.Created -> {
            val location = res.headers[HttpHeaders.Location] ?: return@call null
            val digestSegment = Url(location).segments.lastOrNull() ?: return@call null
            val digest = Digest.parse(digestSegment) ?: return@call null
            Descriptor(mediaType = mediaType, digest = digest, size = body.length.toLong())
          }
          else -> null
        }
      },
    )
  }

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

    val outcome =
      caller.call(
        operation = "repository.supportsRange",
        buildRequest = {
          method = HttpMethod.Head
          url(endpoint)
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        },
        onSuccess = { res -> res.headers["Accept-Ranges"] == "bytes" },
      )
    val rangeSupported = outcome ?: false

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
    private const val DEFAULT_PUSH_CHUNK_SIZE = 5L * 1024 * 1024
  }
}
