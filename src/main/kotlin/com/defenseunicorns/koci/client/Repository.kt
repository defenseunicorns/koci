/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.client

import com.defenseunicorns.koci.auth.ACTION_DELETE
import com.defenseunicorns.koci.auth.ACTION_PULL
import com.defenseunicorns.koci.auth.ACTION_PUSH
import com.defenseunicorns.koci.auth.appendScopes
import com.defenseunicorns.koci.auth.scopeRepository
import com.defenseunicorns.koci.http.Router
import com.defenseunicorns.koci.models.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.Reference
import com.defenseunicorns.koci.models.content.Descriptor
import com.defenseunicorns.koci.models.content.Digest
import com.defenseunicorns.koci.models.content.Index
import com.defenseunicorns.koci.models.content.Manifest
import com.defenseunicorns.koci.models.content.Platform
import com.defenseunicorns.koci.models.content.UploadStatus
import com.defenseunicorns.koci.models.content.Versioned
import com.defenseunicorns.koci.models.errors.OCIError
import com.defenseunicorns.koci.models.errors.OCIResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/**
 * Extracts upload status from HTTP response headers for resumable uploads.
 *
 * Parses Location and Range headers to determine upload state and handles the optional
 * OCI-Chunk-Min-Length header.
 *
 * @return [UploadStatus] with location URL, byte offset, and minimum chunk size
 * @throws IllegalStateException if required headers are missing or malformed
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#resuming-an-upload">OCI
 *   Distribution Spec: Resuming an Upload</a>
 */
fun Headers.toUploadStatus(): UploadStatus {
  val location = checkNotNull(this[HttpHeaders.Location]) { "missing Location header" }
  val range = checkNotNull(this[HttpHeaders.Range]) { "missing Range header" }
  val re = Regex("^([0-9]+)-([0-9]+)\$")
  val offset = checkNotNull(re.matchEntire(range)?.groupValues?.last()) { "invalid Range header" }

  // this header MAY not exist
  val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

  return UploadStatus(location, offset.toLong(), minChunk)
}

/**
 * OCI spec compliant repository client.
 *
 * Supports all required operations including pulling/pushing blobs and manifests, content
 * verification, resumable uploads, cross-repository mounting, and tag management.
 *
 * @property client HTTP client for registry communication
 * @property router URL routing for registry endpoints
 * @property name Repository name as retrieved from a reference (e.g., "[host]/[name]:[tag]")
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md">OCI spec</a>
 */
@Suppress("detekt:TooManyFunctions")
class Repository(
  private val client: HttpClient,
  private val router: Router,
  private val name: String,
  private val coordinator: TransferCoordinator = TransferCoordinator(),
) {
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
  suspend fun exists(descriptor: Descriptor): OCIResult<Boolean> {
    return try {
      val endpoint =
        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE,
          INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)

          else -> router.blob(name, descriptor)
        }
      val response =
        client.head(endpoint) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      OCIResult.ok(true)
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to check existence: ${e.message}", e))
    }
  }

  /**
   * Resolves a tag to a descriptor, with optional platform filtering for index manifests.
   *
   * First performs a HEAD request to determine content type, then handles accordingly:
   * - For regular manifests: Returns descriptor directly
   * - For index manifests: Returns full index or uses platformResolver to select specific platform
   *
   * @param tag Tag to resolve
   * @param platformResolver Optional function to select specific platform from index manifest
   * @throws com.defenseunicorns.koci.models.errors.OCIException.PlatformNotFound if platformResolver
   *   provided but no matching platform found
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  suspend fun resolve(
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): OCIResult<Descriptor> {
    return try {
      val endpoint = router.manifest(name, tag)
      val response =
        client.head(endpoint) {
          accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          accept(ContentType.parse(INDEX_MEDIA_TYPE))
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        }

      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }

      when (response.contentType()?.toString()) {
        INDEX_MEDIA_TYPE -> {
          client
            .prepareGet(endpoint) {
              accept(ContentType.parse(INDEX_MEDIA_TYPE))
              attributes.appendScopes(scopeRepository(name, ACTION_PULL))
            }
            .execute { res ->
              val descriptor =
                when (platformResolver) {
                  null -> {
                    Descriptor.fromInputStream(
                      mediaType = INDEX_MEDIA_TYPE,
                      stream = res.body() as InputStream,
                    )
                  }

                  else -> {
                    val index = Json.decodeFromStream<Index>(res.body())
                    index.manifests.firstOrNull { desc ->
                      desc.platform != null && platformResolver(desc.platform)
                    }
                  }
                }

              if (descriptor == null) {
                return@execute OCIResult.err(
                  OCIError.DescriptorNotFound("No matching platform found in index")
                )
              }

              OCIResult.ok(descriptor)
            }
        }

        MANIFEST_MEDIA_TYPE -> {
          client
            .prepareGet(endpoint) {
              accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
              attributes.appendScopes(scopeRepository(name, ACTION_PULL))
            }
            .execute { res ->
              val descriptor =
                Descriptor.fromInputStream(
                  mediaType = MANIFEST_MEDIA_TYPE,
                  stream = res.body() as InputStream,
                )
              OCIResult.ok(descriptor)
            }
        }

        else ->
          OCIResult.err(
            OCIError.UnsupportedManifest(
              response.contentType()?.toString() ?: "unknown",
              "Unsupported content type for manifest"
            )
          )
      }
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to resolve tag: ${e.message}", e))
    }
  }

  /**
   * Removes a blob or manifest from the repository.
   *
   * Routes to appropriate endpoint based on media type. Note that per OCI spec, registries MAY
   * implement deletion or MAY disable it entirely.
   *
   * @param descriptor Content descriptor to remove
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-blobs">OCI
   *   Distribution Spec: Deleting Blobs</a>
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-manifests">OCI
   *   Distribution Spec: Deleting Manifests</a>
   *
   * TODO: Similarly, a registry MAY implement tag deletion, while others MAY allow deletion only by
   *   manifest.
   */
  suspend fun remove(descriptor: Descriptor): OCIResult<Boolean> {
    return try {
      val endpoint =
        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE,
          INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)

          else -> router.blob(name, descriptor)
        }

      val response =
        client.delete(endpoint) { attributes.appendScopes(scopeRepository(name, ACTION_DELETE)) }
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      OCIResult.ok(true)
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to remove content: ${e.message}", e))
    }
  }

  /**
   * Generic content fetcher with custom processing.
   *
   * Retrieves content and processes it with the provided handler function. Used internally by
   * [manifest], [index], and [pull] methods.
   *
   * @param descriptor Content descriptor with mediaType and digest
   * @param handler Function to process the input stream
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI
   *   Distribution Spec: Pulling Blobs</a>
   */
  suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T {
    return client
      .prepareGet(
        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE,
          INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
          else -> router.blob(name, descriptor)
        }
      ) {
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))

        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
        }
      }
      .execute { res -> res.body<InputStream>().use { stream -> handler(stream) } }
  }

  /**
   * Fetches and deserializes a manifest from the registry.
   *
   * Retrieves a manifest and deserializes it into a Manifest object for programmatic access to
   * layers, config, and annotations.
   *
   * @param descriptor Manifest descriptor with digest and mediaType
   * @throws IllegalArgumentException if descriptor mediaType is not a manifest
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  suspend fun manifest(descriptor: Descriptor): OCIResult<Manifest> {
    if (descriptor.mediaType != MANIFEST_MEDIA_TYPE) {
      return OCIResult.err(
        OCIError.UnsupportedManifest(descriptor.mediaType, "Expected manifest media type")
      )
    }
    return try {
      OCIResult.ok(fetch(descriptor, Json::decodeFromStream))
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to fetch manifest: ${e.message}", e))
    }
  }

  /**
   * Fetches and deserializes an index manifest (multi-platform manifest).
   *
   * Retrieves an index manifest and deserializes it into an Index object for accessing
   * platform-specific manifests in multi-platform images.
   *
   * @param descriptor Index descriptor with digest and mediaType
   * @throws IllegalArgumentException if descriptor mediaType is not an index
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  suspend fun index(descriptor: Descriptor): OCIResult<Index> {
    if (descriptor.mediaType != INDEX_MEDIA_TYPE) {
      return OCIResult.err(
        OCIError.UnsupportedManifest(descriptor.mediaType, "Expected index media type")
      )
    }
    return try {
      OCIResult.ok(fetch(descriptor, Json::decodeFromStream))
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to fetch index: ${e.message}", e))
    }
  }

  /**
   * Lists all tags in the repository. Retrieves available tags for content discovery.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Tags</a>
   *
   * TODO: Implement pagination support as described in the specification
   */
  suspend fun tags(): OCIResult<TagsResponse> {
    return try {
      val response =
        client.get(router.tags(name)) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      OCIResult.ok(Json.decodeFromString(response.body()))
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to fetch tags: ${e.message}", e))
    }
  }

  /**
   * Pulls an image by tag and stores it in the provided layout.
   *
   * Resolves tag to descriptor, then pulls manifest and all referenced blobs. For multi-platform
   * images, uses platformResolver to select specific platform.
   *
   * Emits progress updates as OCIResult<Int> where the value is percentage complete (0-100).
   * Errors are emitted as OCIResult.Err and the flow completes.
   *
   * @param tag Tag to pull
   * @param store Layout to store content in
   * @param platformResolver Optional function to select platform from index
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  fun pull(
    tag: String,
    store: Layout,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Flow<OCIResult<Int>> = flow {
    val desc =
      when (val result = resolve(tag, platformResolver)) {
        is OCIResult.Ok -> result.value
        is OCIResult.Err -> {
          emit(OCIResult.err(result.error))
          return@flow
        }
      }

    pull(desc, store).collect { progressResult ->
      when (progressResult) {
        is OCIResult.Ok -> emit(progressResult)
        is OCIResult.Err -> {
          emit(progressResult)
          return@collect
        }
      }
    }

    // After successful pull, tag the content
    val ref = Reference(registry = router.base(), repository = name, reference = tag)
    val ok = store.exists(desc).getOrNull() ?: false
    if (!ok) {
      emit(OCIResult.err(OCIError.Generic("Incomplete pull: content not found after download")))
      return@flow
    }

    when (val result = store.tag(desc, ref)) {
      is OCIResult.Err -> {
        emit(OCIResult.err(result.error))
        return@flow
      }
      is OCIResult.Ok -> emit(OCIResult.ok(100))
    }
  }

  /**
   * Pulls content by descriptor and stores it in the provided layout.
   *
   * Handles different content types appropriately (manifests, indices, blobs). For manifests and
   * indices, pulls all referenced content recursively.
   *
   * Emits progress updates as OCIResult<Int> where the value is percentage complete (0-100).
   * Errors are emitted as OCIResult.Err and the flow completes.
   *
   * @param descriptor Content descriptor to pull
   * @param store Layout to store content in
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun pull(descriptor: Descriptor, store: Layout): Flow<OCIResult<Int>> = flow {
    when (descriptor.mediaType) {
      INDEX_MEDIA_TYPE -> {
        if (store.exists(descriptor).getOrNull() == true) {
          emit(OCIResult.ok(100))
          return@flow
        }

        val indexContent =
          when (val result = index(descriptor)) {
            is OCIResult.Ok -> result.value
            is OCIResult.Err -> {
              emit(OCIResult.err(result.error))
              return@flow
            }
          }

        val total = indexContent.manifests.size
        var completedPulls = 0
        indexContent.manifests.forEach { manifestDescriptor ->
          pull(manifestDescriptor, store).collect { progressResult ->
            when (progressResult) {
              is OCIResult.Ok -> {
                val currentManifestContribution = progressResult.value.toDouble() / 100.0
                val overallProgress =
                  ((completedPulls + currentManifestContribution) / total * 100).roundToInt()
                emit(OCIResult.ok(overallProgress))
              }
              is OCIResult.Err -> {
                emit(progressResult)
                return@collect
              }
            }
          }
          completedPulls += 1
        }
        copy(descriptor, store).collect { result ->
          when (result) {
            is OCIResult.Err -> {
              emit(result)
              return@collect
            }
            is OCIResult.Ok -> {} // Ignore intermediate progress for index
          }
        }
        emit(OCIResult.ok(100))
      }

      MANIFEST_MEDIA_TYPE -> {
        if (store.exists(descriptor).getOrNull() == true) {
          emit(OCIResult.ok(100))
          return@flow
        }

        val manifestContent =
          when (val result = manifest(descriptor)) {
            is OCIResult.Ok -> result.value
            is OCIResult.Err -> {
              emit(OCIResult.err(result.error))
              return@flow
            }
          }

        val layersToFetch = manifestContent.layers.toMutableList() + manifestContent.config
        val total = layersToFetch.sumOf { it.size } + manifestContent.config.size + descriptor.size

        val acc = AtomicInteger(0)

        layersToFetch
          .asFlow()
          .map { layer ->
            flow {
              copy(layer, store).collect { result ->
                when (result) {
                  is OCIResult.Ok -> {
                    val curr = acc.addAndGet(result.value)
                    emit((curr.toDouble() * 100 / total).roundToInt())
                  }
                  is OCIResult.Err -> throw IllegalStateException("Layer download failed: ${result.error}")
                }
              }
            }
          }
          .flattenMerge(concurrency = 3) // TODO: figure out best API to expose concurrency settings
          .onCompletion { cause ->
            if (cause == null) {
              copy(descriptor, store).collect { result ->
                when (result) {
                  is OCIResult.Ok -> {
                    val curr = acc.addAndGet(result.value)
                    emit((curr.toDouble() * 100 / total).roundToInt())
                  }
                  is OCIResult.Err -> throw IllegalStateException("Manifest download failed: ${result.error}")
                }
              }
            }
          }
          .collect { progress -> emit(OCIResult.ok(progress)) }
      }

      else -> {
        copy(descriptor, store).collect { result ->
          when (result) {
            is OCIResult.Ok -> emit(result)
            is OCIResult.Err -> {
              emit(result)
              return@collect
            }
          }
        }
      }
    }
  }

  @Volatile private var supportsRange: Boolean? = null

  /**
   * This endpoint may also support RFC7233 compliant range requests. Support can be detected by
   * issuing a HEAD request. If the header `Accept-Ranges: bytes` is returned, range requests can be
   * used to fetch partial content.
   */
  private suspend fun supportsRange(descriptor: Descriptor): Boolean {
    supportsRange?.let {
      return it
    }

    require(descriptor.mediaType != MANIFEST_MEDIA_TYPE)
    require(descriptor.mediaType != INDEX_MEDIA_TYPE)

    val response =
      runCatching {
          client.head(router.blob(name, descriptor)) {
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
          }
        }
        .getOrNull()
    val rangeSupported = response?.headers?.get("Accept-Ranges") == "bytes"

    return synchronized(this) {
      supportsRange
        ?: run {
          supportsRange = rangeSupported
          rangeSupported
        }
    }
  }

  /**
   * Downloads blob and stores it in layout, with support for resumable downloads when registry
   * supports range requests.
   *
   * Uses the download coordinator to prevent duplicate downloads when multiple operations request
   * the same descriptor concurrently.
   *
   * Emits progress updates as OCIResult<Int> where the value is bytes downloaded.
   * Errors are emitted as OCIResult.Err and the flow completes.
   *
   * @param descriptor Blob descriptor to copy
   * @param store Layout to store blob in
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI
   *   Distribution Spec: Pulling Blobs</a>
   *
   * Note: For complete images or manifests, use [pull] methods instead.
   */
  private suspend fun copy(descriptor: Descriptor, store: Layout): Flow<OCIResult<Int>> = 
    coordinator.download(descriptor) {
      actualCopy(descriptor, store)
    }

  /**
   * Performs the actual download operation.
   *
   * This is called by the coordinator when this is the first request for a descriptor.
   * Other concurrent requests for the same descriptor will wait for this to complete.
   */
  private fun actualCopy(descriptor: Descriptor, store: Layout): Flow<OCIResult<Int>> = flow {
    val existsResult = store.exists(descriptor)

    // if the descriptor is 100% downloaded w/ size and sha matching, early return
    if (existsResult.getOrNull() == true) {
      emit(OCIResult.ok(descriptor.size.toInt()))
      return@flow
    }

    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)

        else -> router.blob(name, descriptor)
      }

    // Handle partial downloads if exists check revealed size/digest mismatch
    val resumeFrom =
      when (val error = existsResult.errorOrNull()) {
        is OCIError.SizeMismatch -> {
          if (supportsRange(descriptor)) {
            // Resume from where we left off
            emit(OCIResult.ok(error.actual.toInt()))
            error.actual
          } else {
            // Can't resume, remove partial download
            when (val removeResult = store.remove(descriptor)) {
              is OCIResult.Err -> {
                emit(OCIResult.err(removeResult.error))
                return@flow
              }
              is OCIResult.Ok -> null
            }
          }
        }

        is OCIError.DigestMismatch -> {
          // Digest mismatch, remove and start over
          when (val removeResult = store.remove(descriptor)) {
            is OCIResult.Err -> {
              emit(OCIResult.err(removeResult.error))
              return@flow
            }
            is OCIResult.Ok -> null
          }
        }

        else -> null
      }

    try {
      client
        .prepareGet(endpoint) {
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))

          when (descriptor.mediaType) {
            MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
            INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
          }

          // Add range header if resuming
          resumeFrom?.let { start -> headers.append("Range", "bytes=$start-${descriptor.size - 1}") }
        }
        .execute { response ->
          if (!response.status.isSuccess()) {
            emit(parseHTTPError(response))
            return@execute
          }

          response.body<InputStream>().use { stream ->
            store.push(descriptor, stream).collect { prog -> emit(OCIResult.ok(prog)) }
          }
        }
    } catch (e: Exception) {
      emit(OCIResult.err(OCIError.IOError("Failed to download blob: ${e.message}", e)))
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
  @Suppress("detekt:NestedBlockDepth", "detekt:ReturnCount")
  private suspend fun startOrResumeUpload(descriptor: Descriptor): OCIResult<UploadStatus> {
    return when (val prev = uploading[descriptor]) {
      null -> {
        try {
          val response =
            client.post(router.uploads(name)) {
              headers[HttpHeaders.ContentLength] = 0.toString()
              attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
            }
          if (response.status != HttpStatusCode.Accepted) {
            return parseHTTPError(response)
          }
          OCIResult.ok(response.headers.toUploadStatus())
        } catch (e: Exception) {
          OCIResult.err(OCIError.IOError("Failed to start upload: ${e.message}", e))
        }
      }

      else -> {
        if (prev.offset > 0) {
          try {
            val response =
              client.get(router.parseUploadLocation(prev.location)) {
                attributes.appendScopes(scopeRepository(name, ACTION_PULL))
              }

            when (response.status) {
              HttpStatusCode.NoContent -> {
                val curr = response.headers.toUploadStatus()
                if (curr.offset != prev.offset) {
                  prev.offset = curr.offset
                }
                OCIResult.ok(prev)
              }
              HttpStatusCode.NotFound -> {
                // Upload session expired, start a new one
                uploading.remove(descriptor)
                startOrResumeUpload(descriptor)
              }
              else -> parseHTTPError(response)
            }
          } catch (e: Exception) {
            OCIResult.err(OCIError.IOError("Failed to resume upload: ${e.message}", e))
          }
        } else {
          OCIResult.ok(prev)
        }
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
   * Emits progress updates as OCIResult<Long> where the value is bytes uploaded.
   * Errors are emitted as OCIResult.Err and the flow completes.
   *
   * @param stream Input stream containing blob data
   * @param expected Descriptor with expected size and digest
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs">OCI
   *   Distribution Spec: Pushing Blobs</a>
   */
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
  fun push(stream: InputStream, expected: Descriptor): Flow<OCIResult<Long>> =
    flow {
      try {
        if (exists(expected).getOrNull() == true) {
          emit(OCIResult.ok(expected.size))
          withContext(Dispatchers.IO) { stream.close() }
          return@flow
        }

        val start =
          when (val result = startOrResumeUpload(expected)) {
            is OCIResult.Ok -> result.value.also { uploading[expected] = it }
            is OCIResult.Err -> {
              emit(OCIResult.err(result.error))
              return@flow
            }
          }

        if (start.minChunkSize == 0L) {
          start.minChunkSize = 5 * 1024 * 1024
        }

        when (val bytesLeft = expected.size - start.offset) {
          in 1..start.minChunkSize -> {
            val response =
              client.put(start.location) {
                url { encodedParameters.append("digest", expected.digest.toString()) }
                headers { append(HttpHeaders.ContentLength, expected.size.toString()) }
                setBody(stream)
                attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
              }

            if (response.status != HttpStatusCode.Created) {
              emit(parseHTTPError(response))
              return@flow
            }

            emit(OCIResult.ok(bytesLeft))
          }

          else -> {
            var offset = start.offset
            stream.use { s ->
              if (offset > 0) withContext(Dispatchers.IO) { s.skipNBytes(offset + 1) }

              while (currentCoroutineContext().isActive) {
                val chunk = withContext(Dispatchers.IO) { s.readNBytes(start.minChunkSize.toInt()) }

                if (chunk.isEmpty()) {
                  break
                }

                val endRange = offset + chunk.size - 1
                val currentLocation =
                  uploading[expected]?.location
                    ?: run {
                      emit(OCIResult.err(OCIError.Generic("Upload location unexpectedly null")))
                      return@flow
                    }

                val response =
                  client.patch(router.parseUploadLocation(currentLocation)) {
                    setBody(chunk)
                    headers { append(HttpHeaders.ContentRange, "$offset-$endRange") }
                    attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                  }

                if (response.status != HttpStatusCode.Accepted) {
                  emit(parseHTTPError(response))
                  return@flow
                }

                val status = response.headers.toUploadStatus()
                uploading[expected] = status
                offset = status.offset + 1

                emit(OCIResult.ok(offset))
              }
            }

            val final =
              uploading[expected]?.location
                ?: run {
                  emit(OCIResult.err(OCIError.Generic("Upload location unexpectedly null")))
                  return@flow
                }

            val finalResponse =
              client.put(final) {
                url { encodedParameters.append("digest", expected.digest.toString()) }
                attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
              }

            if (finalResponse.status != HttpStatusCode.Created) {
              emit(parseHTTPError(finalResponse))
              return@flow
            }
          }
        }

        uploading.remove(expected)
      } catch (e: Exception) {
        emit(OCIResult.err(OCIError.IOError("Failed to push blob: ${e.message}", e)))
      }
    }

  /**
   * Tags a manifest or index in the repository.
   *
   * Pushes content to registry and associates it with a tag. Handles content type negotiation based
   * on whether content is a manifest or index. Tags must match regex: [TagRegex].
   *
   * @param content Manifest or index content to tag
   * @param tag Tag to associate with the content
   * @throws IllegalArgumentException if tag format is invalid
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests">OCI
   *   Distribution Spec: Pushing Manifests</a>
   */
  suspend fun tag(content: Versioned, ref: String): OCIResult<Descriptor> {
    // Validate tag format
    if (TagRegex.matchEntire(ref) == null) {
      return OCIResult.err(OCIError.Generic("Invalid tag format: $ref"))
    }

    return try {
      val (ct, txt) =
        when (content) {
          is Manifest -> {
            val ct = content.mediaType ?: MANIFEST_MEDIA_TYPE
            val txt = Json.encodeToString(Manifest.serializer(), content)
            ct to txt
          }

          is Index -> {
            val ct = content.mediaType ?: INDEX_MEDIA_TYPE
            val txt = Json.encodeToString(Index.serializer(), content)
            ct to txt
          }
        }

      val response =
        client.put(router.manifest(name, ref)) {
          contentType(ContentType.parse(ct))
          setBody(txt)
          attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
        }

      if (response.status != HttpStatusCode.Created) {
        return parseHTTPError(response)
      }

      // get digest from Location header
      val location =
        response.headers[HttpHeaders.Location]
          ?: return OCIResult.err(OCIError.Generic("Missing Location header in response"))
      val dg = Url(location).segments.last()

      OCIResult.ok(Descriptor(ct, Digest(dg), txt.length.toLong()))
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to tag content: ${e.message}", e))
    }
  }

  /**
   * Mounts a blob from another repository.
   *
   * Reuses blobs that already exist in registry without downloading and re-uploading. Handles both
   * successful mounts and fallback to creating an upload session when mounting is not supported or
   * fails.
   *
   * @param descriptor Blob descriptor to mount (must not be a manifest or index)
   * @param sourceRepository Source repository from which to mount the blob
   * @throws IllegalArgumentException if descriptor is a manifest or index
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#mounting-a-blob-from-another-repository">OCI
   *   Distribution Spec: Mounting a Blob</a>
   */
  suspend fun mount(descriptor: Descriptor, sourceRepository: String): OCIResult<Boolean> {
    if (descriptor.mediaType == MANIFEST_MEDIA_TYPE || descriptor.mediaType == INDEX_MEDIA_TYPE) {
      return OCIResult.err(OCIError.Generic("Cannot mount manifests or indexes"))
    }

    return try {
      // If the blob is already being uploaded, don't try to mount it
      if (uploading.containsKey(descriptor)) {
        return OCIResult.ok(false)
      }

      // Check if already exists
      when (val existsResult = exists(descriptor)) {
        is OCIResult.Ok -> if (existsResult.value) return OCIResult.ok(true)
        is OCIResult.Err -> return existsResult
      }

      val mountUrl = router.blobMount(name, sourceRepository, descriptor)
      val response =
        client.post(mountUrl) {
          headers[HttpHeaders.ContentLength] = "0"
          attributes.appendScopes(
            scopeRepository(name, ACTION_PULL, ACTION_PUSH),
            scopeRepository(sourceRepository, ACTION_PULL),
          )
        }

      when (response.status) {
        HttpStatusCode.Created -> {
          val locationHeader =
            response.headers[HttpHeaders.Location]
              ?: return OCIResult.err(
                OCIError.Generic("Registry did not provide a Location header in mount response")
              )
          OCIResult.ok(true)
        }

        HttpStatusCode.Accepted -> {
          val uploadStatus = response.headers.toUploadStatus()
          uploading[descriptor] = uploadStatus
          OCIResult.ok(false)
        }

        else -> parseHTTPError(response)
      }
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to mount blob: ${e.message}", e))
    }
  }
}
