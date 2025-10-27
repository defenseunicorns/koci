/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.client

import com.defenseunicorns.koci.KociLogger
import com.defenseunicorns.koci.TransferCoordinator
import com.defenseunicorns.koci.api.models.Descriptor
import com.defenseunicorns.koci.api.models.Index
import com.defenseunicorns.koci.api.models.Manifest
import com.defenseunicorns.koci.api.models.Platform
import com.defenseunicorns.koci.api.models.Reference
import com.defenseunicorns.koci.api.models.TagsResponse
import com.defenseunicorns.koci.api.models.UploadStatus
import com.defenseunicorns.koci.api.models.Versioned
import com.defenseunicorns.koci.auth.ACTION_DELETE
import com.defenseunicorns.koci.auth.ACTION_PULL
import com.defenseunicorns.koci.auth.ACTION_PUSH
import com.defenseunicorns.koci.auth.appendScopes
import com.defenseunicorns.koci.auth.scopeRepository
import com.defenseunicorns.koci.http.Router
import com.defenseunicorns.koci.http.parseHTTPError
import com.defenseunicorns.koci.models.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.tagRegex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/**
 * OCI spec compliant repository client.
 *
 * Supports all required operations including pulling/pushing blobs and manifests, content
 * verification, resumable uploads, cross-repository mounting, and tag management.
 *
 * @property client HTTP client for registry communication
 * @property router Url routing for registry endpoints
 * @property name Repository name as retrieved from a reference (e.g., "[host]/[name]:[tag]")
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md">OCI spec</a>
 */
class Repository
internal constructor(
  private val client: HttpClient,
  private val router: Router,
  private val name: String,
  private val logger: KociLogger,
  private val transferCoordinator: TransferCoordinator,
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
  suspend fun exists(descriptor: Descriptor): Boolean {
    try {
      logger.d("Checking existence of ${descriptor.digest}")
      val endpoint =
        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE,
          INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
          else -> router.blob(name, descriptor)
        }
      val response =
        client.head(endpoint) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }

      if (!response.status.isSuccess()) {
        logger.d(
          """
					Descriptor does not exist: ${descriptor.digest}
					HTTP status code: ${response.status}
					HTTP response: ${response.bodyAsText()}
					"""
            .trimIndent()
        )
        return false
      }

      logger.d("Descriptor exists: ${descriptor.digest}")

      return true
    } catch (e: Exception) {
      logger.e("Failed to check existence: ${descriptor.digest}", e)
      return false
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
   * @throws com.defenseunicorns.koci.api.OCIException.PlatformNotFound if platformResolver provided
   *   but no matching platform found
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  suspend fun resolve(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Descriptor? {
    return try {
      logger.d("Resolving tag: $name:$tag")
      val endpoint = router.manifest(name, tag)
      val response =
        client.head(endpoint) {
          accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          accept(ContentType.parse(INDEX_MEDIA_TYPE))
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        }

      if (!response.status.isSuccess()) {
        logger.e(
          """
					HTTP code: ${response.status}
					HTTP response: ${response.bodyAsText()}
				"""
            .trimIndent()
        )
        return null
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
                logger.e("No matching platform found in index for $name:$tag")
              } else {
                logger.d("Resolved $name:$tag to ${descriptor.digest}")
              }
              descriptor
            }
        }

        MANIFEST_MEDIA_TYPE -> {
          client
            .prepareGet(endpoint) {
              accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
              attributes.appendScopes(scopeRepository(name, ACTION_PULL))
            }
            .execute { res ->
              Descriptor.fromInputStream(
                mediaType = MANIFEST_MEDIA_TYPE,
                stream = res.body() as InputStream,
              )
            }
        }

        else -> {
          logger.e("Unsupported content type for $name:$tag: ${response.contentType()}")
          null
        }
      }
    } catch (e: Exception) {
      logger.e("Failed to resolve tag: $name:$tag", e)
      null
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
  suspend fun remove(descriptor: Descriptor): Boolean {
    try {
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
      return true
    } catch (e: Exception) {
      logger.e("Failed to remove content", e)
      return false
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
  private suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T {
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
  suspend fun manifest(descriptor: Descriptor): Manifest? {
    if (descriptor.mediaType != MANIFEST_MEDIA_TYPE) {
      logger.e("Expected manifest media type")
      return null
    }
    return try {
      fetch(descriptor, Json::decodeFromStream)
    } catch (e: Exception) {
      logger.e("Failed to fetch manifest", e)
      null
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
  suspend fun index(descriptor: Descriptor): Index? {
    if (descriptor.mediaType != INDEX_MEDIA_TYPE) {
      logger.e("Expected index media type, got: ${descriptor.mediaType}")
      return null
    }
    return try {
      fetch(descriptor, Json::decodeFromStream)
    } catch (e: Exception) {
      logger.e("Failed to fetch index", e)
      null
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
  suspend fun tags(): TagsResponse? {
    return try {
      val response =
        client.get(router.tags(name)) {
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        }
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      Json.decodeFromString(response.body())
    } catch (e: Exception) {
      logger.e("Failed to fetch tags", e)
      null
    }
  }

  /**
   * Pulls an image by tag and stores it in the provided layout.
   *
   * Resolves tag to descriptor, then pulls manifest and all referenced blobs. For multi-platform
   * images, uses platformResolver to select specific platform.
   *
   * Emits progress updates as OCIResult<Int> where the value is percentage complete (0-100). Errors
   * are emitted as OCIResult.Err and the flow completes.
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
  ): Flow<Double?> = flow {
    logger.d("Pulling $name:$tag")
    val desc = resolve(tag, platformResolver)

    if (desc == null) {
      emit(null)
      return@flow
    }

    pull(desc, store).collect { progressResult ->
      when (progressResult == null) {
        true -> return@collect emit(null)
        false -> emit(progressResult)
      }
    }

    // After successful pull, tag the content
    val ref = Reference(registry = router.base(), repository = name, reference = tag)
    val ok = store.exists(desc)
    if (!ok) {
      logger.e("Incomplete pull for $name:$tag: content not found after download")
      return@flow emit(null)
    }

    logger.d("Successfully pulled $name:$tag")

    when (store.tag(desc, ref)) {
      false -> {
        logger.e("Could not tag: $desc with $ref")
        return@flow emit(null)
      }
      true -> emit(FINISHED_AMOUNT)
    }
  }

  /**
   * Pulls content by descriptor and stores it in the provided layout.
   *
   * Handles different content types appropriately (manifests, indices, blobs). For manifests and
   * indices, pulls all referenced content recursively.
   *
   * Emits progress updates as OCIResult<Int> where the value is percentage complete (0-100). Errors
   * are emitted as OCIResult.Err and the flow completes.
   *
   * @param descriptor Content descriptor to pull
   * @param store Layout to store content in
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun pull(descriptor: Descriptor, store: Layout): Flow<Double?> = flow {
    logger.d("Pulling descriptor: ${descriptor.digest}")

    if (store.exists(descriptor)) {
      emit(FINISHED_AMOUNT)
      return@flow
    }

    when (descriptor.mediaType) {
      INDEX_MEDIA_TYPE -> {
        val indexContent = index(descriptor) ?: return@flow emit(null)
        val manifests = indexContent.manifests
        val totalBytes = manifests.sumOf { it.size } + descriptor.size

        var completedBytes = 0.0

        manifests.forEach { manifestDesc ->
          pull(manifestDesc, store).collect { childProgress ->
            if (childProgress == null) {
              return@collect emit(null)
            }

            // Weight child's progress by its total size
            val childContribution = (childProgress / 100.0) * manifestDesc.size
            val overallProgress = ((completedBytes + childContribution) / totalBytes) * 100
            emit(overallProgress)
          }

          completedBytes += manifestDesc.size
        }

        // Now pull the descriptor itself
        downloadWithProgress(descriptor, store).collect { progress ->
          if (progress == null) return@collect emit(null)
          val overall = ((completedBytes + (progress / 100.0 * descriptor.size)) / totalBytes) * 100
          emit(overall)
        }

        emit(FINISHED_AMOUNT)
      }

      MANIFEST_MEDIA_TYPE -> {
        val manifestContent = manifest(descriptor) ?: return@flow emit(null)
        val layers = manifestContent.layers + manifestContent.config
        val totalBytes = layers.sumOf { it.size } + descriptor.size

        val completedBytes = AtomicLong(0)

        layers
          .asFlow()
          .map { layer ->
            flow {
              download(layer, store).collect { result ->
                when (result == null) {
                  true -> return@collect emit(null)
                  false -> {
                    completedBytes.addAndGet(layer.size)
                    val overall = (completedBytes.get().toDouble() / totalBytes) * 100
                    emit(overall)
                  }
                }
              }
            }
          }
          .flattenMerge(concurrency = 3)
          .collect { emit(it) }

        // Pull the manifest itself at the end
        downloadWithProgress(descriptor, store).collect { progress ->
          if (progress == null) return@collect emit(null)
          val overall =
            ((completedBytes.get() + (progress / 100.0 * descriptor.size)) / totalBytes) * 100
          emit(overall)
        }

        emit(FINISHED_AMOUNT)
      }

      else -> {
        downloadWithProgress(descriptor, store).collect { progress -> emit(progress) }
        emit(FINISHED_AMOUNT)
      }
    }
  }

  private suspend fun downloadWithProgress(target: Descriptor, store: Layout): Flow<Double?> =
    flow {
      download(target, store).collect { result ->
        when (result == null) {
          true -> return@collect emit(null)
          false -> emit(result)
        }
      }
      emit(FINISHED_AMOUNT)
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
   * Downloads a descriptor from the registry, coordinating with concurrent requests.
   *
   * Uses the transfer coordinator to prevent duplicate downloads of the same descriptor.
   */
  private fun download(descriptor: Descriptor, store: Layout): Flow<Double?> =
    transferCoordinator.transfer(descriptor = descriptor) { actualDownload(descriptor, store) }

  /**
   * Performs the actual download operation.
   *
   * This is called by the coordinator when this is the first request for a descriptor. Other
   * concurrent requests for the same descriptor will wait for this to complete.
   */
  private fun actualDownload(descriptor: Descriptor, store: Layout): Flow<Double?> = flow {
    logger.d("Downloading descriptor: ${descriptor.digest}")

    // if the descriptor is 100% downloaded w/ size and sha matching, early return
    if (store.exists(descriptor)) {
      emit(FINISHED_AMOUNT)
      return@flow
    }

    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)

        else -> router.blob(name, descriptor)
      }

    val resumeFrom = store.size(descriptor)
    var resumable = false

    // Handle partial downloads if exists check revealed size/digest mismatch
    if (descriptor.size < resumeFrom && supportsRange(descriptor)) {
      resumable = true
      emit(((resumeFrom.toDouble() / descriptor.size.toDouble()).coerceIn(0.0, 1.0)) * 100.0)
    } else {
      if (!store.remove(descriptor)) {
        return@flow emit(null)
      }
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
          if (resumable) {
            headers.append("Range", "bytes=$resumeFrom-${descriptor.size - 1}")
          }
        }
        .execute { response ->
          if (!response.status.isSuccess()) {
            emit(parseHTTPError(response))
            return@execute
          }

          response.body<InputStream>().use { stream ->
            store.push(descriptor, stream).collect { prog -> emit(prog) }
          }
        }
    } catch (e: Exception) {
      logger.e("Failed to copy blob: ${descriptor.digest}", e)
      return@flow emit(null)
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
  private suspend fun startOrResumeUpload(descriptor: Descriptor): UploadStatus? {
    return when (val prev = uploading[descriptor]) {
      null -> {
        val res =
          client.post(router.uploads(name)) {
            headers[HttpHeaders.ContentLength] = 0.toString()
            attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
          }
        if (res.status != HttpStatusCode.Accepted) {
          logger.e("Expected status ${HttpStatusCode.Accepted} but got: ${res.status}")
          return null
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
                  logger.e("Expected status ${HttpStatusCode.NoContent} but got: ${res.status}")
                  return null
                }

                val curr = res.headers.toUploadStatus()
                if (curr.offset != prev.offset) {
                  prev.offset = curr.offset
                }
              }
          } catch (e: Registry.FromResponse) {
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
   * Emits progress updates as OCIResult<Long> where the value is bytes uploaded. Errors are emitted
   * as OCIResult.Err and the flow completes.
   *
   * @param stream Input stream containing blob data
   * @param expected Descriptor with expected size and digest
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs">OCI
   *   Distribution Spec: Pushing Blobs</a>
   */
  fun push(stream: InputStream, expected: Descriptor): Flow<Double?> = flow {
    try {
      logger.d("Pushing blob: ${expected.digest}")
      if (exists(expected)) {
        logger.d("Blob already exists: ${expected.digest}")
        stream.close()
        return@flow emit(FINISHED_AMOUNT)
      }

      val start = startOrResumeUpload(expected) ?: return@flow emit(null)

      if (start.minChunkSize == 0L) {
        start.minChunkSize = MIN_CHUNK_SIZE
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
            return@flow emit(parseHTTPError(response))
          }

          val progress = ((expected.size - bytesLeft).toDouble() / expected.size.toDouble()) * 100.0
          emit(progress)
        }

        else -> {
          var offset = start.offset
          stream.use { s ->
            if (offset > 0) {
              s.skipNBytes(offset)
            }

            while (currentCoroutineContext().isActive) {
              val chunk = s.readNBytes(start.minChunkSize.toInt())
              if (chunk.isEmpty()) break

              val endRange = offset + chunk.size - 1
              val currentLocation =
                uploading[expected]?.location
                  ?: run {
                    logger.e("Upload location unexpectedly null")
                    emit(null)
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

              val progress = (offset.toDouble() / expected.size.toDouble()) * 100.0
              emit(progress)
            }
          }

          val finalLocation =
            uploading[expected]?.location
              ?: run {
                logger.e("Upload location unexpectedly null")
                emit(null)
                return@flow
              }

          val finalResponse =
            client.put(finalLocation) {
              url { encodedParameters.append("digest", expected.digest.toString()) }
              attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
            }

          if (finalResponse.status != HttpStatusCode.Created) {
            emit(parseHTTPError(finalResponse))
            return@flow
          }

          // ✅ Ensure we finish cleanly with full progress
          emit(FINISHED_AMOUNT)
        }
      }

      uploading.remove(expected)
      logger.d("Successfully pushed blob: ${expected.digest}")
    } catch (e: Exception) {
      logger.e("Failed to push blob: ${expected.digest}", e)
      emit(null)
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
  suspend fun tag(content: Versioned, ref: String): Boolean {
    // Validate tag format
    if (tagRegex.matchEntire(ref) == null) {
      logger.e("Invalid tag format: $ref")
      return false
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
      val location = response.headers[HttpHeaders.Location]

      if (location == null) {
        logger.e("Missing Location header in response")
        return false
      }

      true
    } catch (e: Exception) {
      logger.e("Failed to tag content", e)
      false
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
  suspend fun mount(descriptor: Descriptor, sourceRepository: String): Boolean {
    if (descriptor.mediaType == MANIFEST_MEDIA_TYPE || descriptor.mediaType == INDEX_MEDIA_TYPE) {
      logger.e("Cannot mount manifests or indexes")
      return false
    }

    try {
      // If the blob is already being uploaded, don't try to mount it
      if (transferCoordinator.isTransferring(descriptor)) {
        return false
      }

      if (exists(descriptor)) {
        return true
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
          if (response.headers[HttpHeaders.Location] == null) {
            logger.e("Registry did not provide a Location header in mount response")
            return false
          }
        }

        HttpStatusCode.Accepted -> {
          val uploadStatus = response.headers.toUploadStatus()
          uploading[descriptor] = uploadStatus
        }

        else -> {
          logger.d(
            """
							HTTP status: ${response.status}
							HTTP response: ${response.bodyAsText()}
						"""
              .trimIndent()
          )
          return false
        }
      }
    } catch (e: Exception) {
      logger.e("Failed to mount blob: ${e.message}", e)
      return false
    }

    return true
  }

  /**
   * Extracts upload status from HTTP response headers for resumable uploads.
   *
   * Parses Location and Range headers to determine upload state and handles the optional
   * OCI-Chunk-Min-Length header.
   *
   * @return [UploadStatus] with location Url, byte offset, and minimum chunk size
   * @throws IllegalStateException if required headers are missing or malformed
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#resuming-an-upload">OCI
   *   Distribution Spec: Resuming an Upload</a>
   */
  private fun Headers.toUploadStatus(): UploadStatus {
    val location = checkNotNull(this[HttpHeaders.Location]) { "missing Location header" }
    val range = checkNotNull(this[HttpHeaders.Range]) { "missing Range header" }
    val re = Regex("^([0-9]+)-([0-9]+)\$")
    val offset = checkNotNull(re.matchEntire(range)?.groupValues?.last()) { "invalid Range header" }

    // this header MAY not exist
    val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

    return UploadStatus(location, offset.toLong(), minChunk)
  }

  companion object {
    private const val FINISHED_AMOUNT = 100.0
    private const val MIN_CHUNK_SIZE = 5L * 1024L * 1024L
  }
}
