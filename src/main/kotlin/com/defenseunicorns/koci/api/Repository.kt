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
import com.defenseunicorns.koci.internal.Layout
import com.defenseunicorns.koci.internal.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.internal.OciConstants.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.internal.Router
import com.defenseunicorns.koci.internal.TagsResponse
import com.defenseunicorns.koci.internal.appendScopes
import com.defenseunicorns.koci.internal.scopeRepository
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
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
) {

  @Volatile private var supportsRange: Boolean? = null

  /**
   * Lists all tags in the repository. Retrieves available tags for content discovery.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Tags</a>
   *
   * TODO: Implement pagination support as described in the specification
   */
  public suspend fun tags(): Result<List<String>> = runCatching {
    val res =
      client.get(router.tags(name)) { attributes.appendScopes(scopeRepository(name, ACTION_PULL)) }
    val tags: TagsResponse = Json.decodeFromString(res.body())

    tags.tags
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
   * @throws OCIException.PlatformNotFound if platformResolver provided but no matching platform
   *   found
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  public suspend fun resolve(
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Result<Descriptor> = runCatching {
    val endpoint = router.manifest(name, tag)
    val response =
      client.head(endpoint) {
        accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
        accept(ContentType.parse(INDEX_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      }

    when (response.contentType()?.toString()) {
      INDEX_MEDIA_TYPE -> {
        client
          .prepareGet(endpoint) {
            accept(ContentType.parse(INDEX_MEDIA_TYPE))
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
          }
          .execute { res ->
            when (platformResolver) {
              null -> {
                Descriptor.fromInputStream(
                  mediaType = INDEX_MEDIA_TYPE,
                  stream = res.body() as InputStream,
                )
              }

              else -> {
                val index = Json.decodeFromStream<Index>(res.body())

                try {
                  index.manifests.first { desc ->
                    desc.platform != null && platformResolver(desc.platform)
                  }
                } catch (_: NoSuchElementException) {
                  throw OCIException.PlatformNotFound(index)
                }
              }
            }
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

      else -> throw OCIException.ManifestNotSupported(endpoint, response.contentType())
    }
  }

  /**
   * Pulls an image by tag and stores it in the provided layout.
   *
   * Resolves tag to descriptor, then pulls manifest and all referenced blobs. For multi-platform
   * images, uses platformResolver to select specific platform.
   *
   * @param tag Tag to pull
   * @param platformResolver Optional function to select platform from index
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  public fun pull(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Flow<Int> =
    channelFlow {
      resolve(tag, platformResolver)
        .map { desc ->
          pull(desc)
            .onCompletion { cause ->
              if (cause == null) {
                val ref = Reference(registry = router.base(), repository = name, reference = tag)
                val ok = store.exists(desc).getOrThrow()
                if (!ok) {
                  throw OCIException.IncompletePull(ref)
                }
                // if pull was successful, tag the resolved desc w/ the image's ref
                store.tag(desc, ref).getOrThrow()
              }
            }
            .collect { progress -> send(progress) }
        }
        .getOrThrow()
    }

  /**
   * Pulls content by descriptor and stores it in the provided layout.
   *
   * Handles different content types appropriately (manifests, indices, blobs). For manifests and
   * indices, pulls all referenced content recursively.
   *
   * @param descriptor Content descriptor to pull
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  public fun pull(descriptor: Descriptor): Flow<Int> = channelFlow {
    when (descriptor.mediaType) {
      INDEX_MEDIA_TYPE -> {
        if (store.exists(descriptor).getOrDefault(false)) {
          send(PROGRESS_COMPLETE)
          return@channelFlow
        }
        val index = index(descriptor).getOrThrow()
        val total = index.manifests.size
        var completedPulls = 0
        index.manifests.forEach { manifestDescriptor ->
          pull(manifestDescriptor).collect { manifestProgress ->
            val currentManifestContribution = manifestProgress.toDouble() / PROGRESS_COMPLETE
            val overallProgress =
              ((completedPulls + currentManifestContribution) / total * PROGRESS_COMPLETE)
                .roundToInt()
            send(overallProgress)
          }
          completedPulls += 1
        }
        copy(descriptor, store).collect()
        send(PROGRESS_COMPLETE)
      }

      MANIFEST_MEDIA_TYPE -> {
        if (store.exists(descriptor).getOrDefault(false)) {
          send(PROGRESS_COMPLETE)
          return@channelFlow
        }
        val manifest = manifest(descriptor).getOrThrow()
        val layersToFetch = manifest.layers.toMutableList() + manifest.config
        val total = layersToFetch.sumOf { it.size } + manifest.config.size + descriptor.size

        val acc = AtomicInteger(0)

        layersToFetch
          .asFlow()
          .map { layer ->
            flow {
              copy(layer, store).collect { progress ->
                val curr = acc.addAndGet(progress)
                emit((curr.toDouble() * PROGRESS_COMPLETE / total).roundToInt())
              }
            }
          }
          // TODO: figure out best API to expose concurrency settings
          .flattenMerge(concurrency = DEFAULT_LAYER_CONCURRENCY)
          .onCompletion { cause ->
            if (cause == null) {
              copy(descriptor, store).collect { progress ->
                val curr = acc.addAndGet(progress)
                emit((curr.toDouble() * PROGRESS_COMPLETE / total).roundToInt())
              }
            }
          }
          .collect { progress -> send(progress) }
      }

      else -> {
        copy(descriptor, store).collect { progress -> send(progress) }
      }
    }
  }

  /**
   * Fetches and deserializes an index manifest (multi-platform manifest).
   *
   * Retrieves an index manifest and deserializes it into an [Index] object for accessing
   * platform-specific manifests in multi-platform images.
   *
   * @param descriptor Index descriptor with digest and mediaType
   * @throws IllegalArgumentException if descriptor mediaType is not an index
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  public suspend fun index(descriptor: Descriptor): Result<Index> = runCatching {
    require(descriptor.mediaType == INDEX_MEDIA_TYPE)
    fetch(descriptor, Json::decodeFromStream)
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
  public suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T {
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
   * Retrieves a manifest and deserializes it into a [Manifest] object for programmatic access to
   * layers, config, and annotations.
   *
   * @param descriptor Manifest descriptor with digest and mediaType
   * @throws IllegalArgumentException if descriptor mediaType is not a manifest
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  @OptIn(ExperimentalSerializationApi::class)
  public suspend fun manifest(descriptor: Descriptor): Result<Manifest> = runCatching {
    require(descriptor.mediaType == MANIFEST_MEDIA_TYPE)
    fetch(descriptor, Json::decodeFromStream)
  }

  /**
   * Copies a blob to a local layout with progress reporting.
   *
   * Downloads blob and stores it in layout, with support for resumable downloads when registry
   * supports range requests.
   *
   * @param descriptor Blob descriptor to copy
   * @param store Layout to store blob in
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI
   *   Distribution Spec: Pulling Blobs</a>
   *
   * Note: For complete images or manifests, use [pull] methods instead.
   */
  private fun copy(descriptor: Descriptor, store: Layout): Flow<Int> = channelFlow {
    val ok = store.exists(descriptor)

    // if the descriptor is 100% downloaded w/ size and sha matching, early return
    if (ok.getOrDefault(false)) {
      send(descriptor.size.toInt())
      return@channelFlow
    }

    val endpoint =
      when (descriptor.mediaType) {
        MANIFEST_MEDIA_TYPE,
        INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)

        else -> router.blob(name, descriptor)
      }

    client
      .prepareGet(endpoint) {
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))

        when (descriptor.mediaType) {
          MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
          INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
        }

        when (val exception = ok.exceptionOrNull()) {
          is OCIException.SizeMismatch -> {
            if (supportsRange(descriptor)) {
              val start = exception.actual

              // fire partial progress has happened
              send(start.toInt())

              headers.append("Range", "bytes=$start-${descriptor.size - 1}")
            } else {
              store.remove(descriptor).getOrThrow()
            }
          }

          is OCIException.DigestMismatch -> {
            store.remove(descriptor).getOrThrow()
          }

          null -> {
            // this branch should never happen
          }

          else -> {
            throw exception
          }
        }
      }
      .execute { response ->
        response.body<InputStream>().use { stream ->
          store.push(descriptor, stream.source()).collect { prog -> send(prog) }
        }
      }
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

  override fun toString(): String = "Repository(name=$name)"

  private companion object {
    private const val PROGRESS_COMPLETE = 100
    private const val DEFAULT_LAYER_CONCURRENCY = 6
  }
}
