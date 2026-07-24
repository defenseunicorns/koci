/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Digest
import com.defenseunicorns.koci.api.Index
import com.defenseunicorns.koci.api.Layout
import com.defenseunicorns.koci.api.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.api.OciConstants.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.api.Platform
import com.defenseunicorns.koci.api.Reference
import com.defenseunicorns.koci.api.TransferEvent
import com.defenseunicorns.koci.api.config.PullConfig
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.buffer
import okio.source

/**
 * Pull side of a [Repository]. Reads manifests, indices, and blobs from the registry into the local
 * [Layout], and exposes the existence and resolve probes used by both the manager and the pusher.
 *
 * Pull runs in two phases. [walkTree] flattens the descriptor tree and fetches every manifest/index
 * into memory, yielding a flat blob list. [execute] then downloads blobs concurrently (bounded by
 * [PullConfig.concurrency]) and stores cached container bytes in post-order so the root only lands
 * after every child is on disk. That order preserves the "manifest present implies children
 * present" invariant on crash.
 *
 * The walk does not short-circuit on locally-present descriptors. That keeps the progress
 * denominator stable across resumed pulls and confirms the registry still has the content.
 */
internal class RepositoryPuller(
  private val name: String,
  private val httpWrapper: HttpWrapper,
  private val router: Router,
  private val store: Layout,
  private val json: Json,
  private val pull: PullConfig,
  private val logger: KociLogger,
) {

  /**
   * Returns `true` if [descriptor] exists on the remote, `false` on any non-success status or
   * transport failure.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#checking-if-content-exists-in-the-registry">OCI
   *   Distribution Spec: Checking if Content Exists</a>
   */
  suspend fun exists(descriptor: Descriptor): Boolean =
    request(
      descriptor = descriptor,
      operation = "repository.exists",
      method = HttpMethod.Head,
      onSuccess = { true },
    ) ?: false

  /**
   * Returns every tag in the repository, or an empty list on failure.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Tags</a>
   *
   * TODO: MOBILE-215 Implement pagination.
   */
  suspend fun tags(): List<String> =
    httpWrapper.call(
      operation = "repository.tags",
      buildRequest = {
        url(router.tags(name))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      },
      onSuccess = { res -> res.body<TagsResponse>().tags },
    ) ?: emptyList()

  /**
   * Resolves a tag to a descriptor. When [platformResolver] is supplied, an index is parsed and a
   * single platform manifest is selected; otherwise the descriptor for the tag itself is returned.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec: Pulling Manifests</a>
   */
  suspend fun resolveManifest(
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Descriptor? {
    val endpoint = router.manifest(name, tag)

    return httpWrapper.call(
      operation = "repository.resolve",
      buildRequest = {
        method = HttpMethod.Get
        url(endpoint)
        accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
        accept(ContentType.parse(INDEX_MEDIA_TYPE))
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
      },
      onSuccess = { res ->
        when (val ct = res.contentType()?.toString()) {
          MANIFEST_MEDIA_TYPE -> manifestDescriptor(res, endpoint, MANIFEST_MEDIA_TYPE)
          INDEX_MEDIA_TYPE ->
            when (platformResolver) {
              null -> manifestDescriptor(res, endpoint, INDEX_MEDIA_TYPE)
              else -> selectPlatform(res.body<Index>(), platformResolver)
            }
          else -> {
            logger.warn { "unsupported manifest content type from $endpoint: $ct" }
            null
          }
        }
      },
    )
  }

  /** Pulls an image by tag into the layout. */
  fun pull(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Flow<TransferEvent> =
    flow {
        val manifest = resolveManifest(tag, platformResolver)
        if (manifest == null) {
          logger.warn { "tag '$tag' could not be resolved in $name" }
          emit(TransferEvent.Failed)
          return@flow
        }

        var failed = false
        pull(manifest).collect { event ->
          if (event is TransferEvent.Failed) {
            failed = true
          }
          emit(event)
        }
        if (failed) {
          return@flow
        }

        val ref =
          Reference.from(registry = router.base().toString(), repository = name, reference = tag)
        when (store.inspect(manifest)) {
          is BlobState.Present -> {
            store.tag(manifest, ref)
            emit(TransferEvent.Progress(PROGRESS_COMPLETE))
          }

          else -> {
            logger.error { "post-pull verification failed for $ref" }
            emit(TransferEvent.Failed)
          }
        }
      }
      .distinctUntilChanged()

  /** Pulls content addressed by [descriptor] into the layout. See class-level docs for the flow. */
  fun pull(descriptor: Descriptor): Flow<TransferEvent> =
    flow {
        when (val walk = walkTree(descriptor, json, logger) { fetchContainer(it) }) {
          null -> emit(TransferEvent.Failed)
          else -> execute(walk).collect { emit(it) }
        }
      }
      .distinctUntilChanged()

  /**
   * Fetches [descriptor]'s bytes and passes the raw [InputStream] to [handler]. Returns `null` when
   * the descriptor has no digest or the request fails. Use this when the standard pull path is too
   * constrained, e.g. streaming directly to disk or verifying with a custom hasher.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI
   *   Distribution Spec: Pulling Blobs</a>
   */
  suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T? =
    request(
      descriptor = descriptor,
      operation = "repository.fetch",
      extraBuild = { acceptForDescriptor(descriptor) },
      onSuccess = { res ->
        res.body<InputStream>().use { stream -> handler(stream.source().buffer().inputStream()) }
      },
    )

  /**
   * Downloads every blob in [walk] concurrently against a single global byte total, then stores
   * each cached manifest/index in post-order. Already-present blobs contribute their full size to
   * progress immediately and skip the GET.
   */
  private fun execute(walk: TreeWalk): Flow<TransferEvent> = channelFlow {
    val total = walk.totalBytes
    if (total == 0L) {
      send(TransferEvent.Progress(PROGRESS_COMPLETE))
      return@channelFlow
    }

    val tracker = ProgressTracker(total)
    val emitJob = launch { for (pct in tracker.channel) send(TransferEvent.Progress(pct)) }

    val blobsOk =
      walk.dispatchBlobs(pull.concurrency) { blob ->
        copyBlob(blob) { bytes -> tracker.update(blob, bytes) }
      }
    if (!blobsOk) {
      tracker.close()
      emitJob.join()
      send(TransferEvent.Failed)
      return@channelFlow
    }

    for ((descriptor, buffer) in walk.containers) {
      when (store.push(descriptor, buffer)) {
        true -> tracker.update(descriptor, descriptor.size)
        false -> {
          tracker.close()
          emitJob.join()
          send(TransferEvent.Failed)
          return@channelFlow
        }
      }
    }

    tracker.close()
    emitJob.join()
    send(TransferEvent.Progress(PROGRESS_COMPLETE))
  }

  /** Fetches a manifest or index body into an in-memory buffer. */
  private suspend fun fetchContainer(descriptor: Descriptor): Buffer? =
    request(
      descriptor = descriptor,
      operation = "repository.container",
      extraBuild = { acceptForDescriptor(descriptor) },
      onSuccess = { res ->
        Buffer().apply { res.body<InputStream>().source().use { source -> writeAll(source) } }
      },
    )

  /**
   * Picks the first manifest descriptor in [index] whose platform matches [platformResolver].
   * Returns null and logs when no platform matches.
   */
  private fun selectPlatform(index: Index, platformResolver: (Platform) -> Boolean): Descriptor? {
    val selected =
      index.manifests.firstOrNull { d -> d.platform != null && platformResolver(d.platform) }
    if (selected == null) {
      logger.debug { "no platform matched in index ${index.manifests.map { it.platform }}" }
    }
    return selected
  }

  /**
   * Builds a [Descriptor] from the response headers or the body if `Docker-Content-Digest` is
   * missing.
   *
   * @see <a
   *   href="https://specs.opencontainers.org/distribution-spec/#legacy-docker-support-http-headers">Docker-Content-Digest</a>
   */
  private suspend fun manifestDescriptor(
    res: HttpResponse,
    endpoint: Url,
    mediaType: String,
  ): Descriptor? {
    val dockerContentDigest = res.headers["Docker-Content-Digest"]

    return when (dockerContentDigest == null) {
      true -> Descriptor.fromInputStream(stream = res.body(), mediaType = mediaType)
      false -> {
        val digest =
          Digest.parse(dockerContentDigest)
            ?: run {
              logger.warn { "unparseable digest '$dockerContentDigest' from $endpoint" }
              return null
            }
        val size =
          res.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            ?: run {
              logger.warn { "missing or invalid Content-Length in resolve response from $endpoint" }
              return null
            }
        Descriptor(mediaType = mediaType, digest = digest, size = size)
      }
    }
  }

  /**
   * Copies one blob to the layout. [onBytes] reports cumulative bytes-on-disk, including any
   * resumed prefix. Already-present blobs skip the GET and report their full size immediately.
   * Returns `true` on success, `false` on any failure.
   */
  private suspend fun copyBlob(descriptor: Descriptor, onBytes: (Long) -> Unit): Boolean {
    val state = store.inspect(descriptor)
    if (state is BlobState.Present) {
      onBytes(descriptor.size)
      return true
    }
    val resumeStart =
      when (state) {
        is BlobState.Partial ->
          when (supportsRange(descriptor)) {
            true -> state.bytesOnDisk
            false -> null
          }

        else -> null
      }
    when (resumeStart) {
      null -> store.remove(descriptor) // Clear corrupt or non-resumable state before restarting.
      else -> onBytes(resumeStart)
    }

    val outcome =
      request(
        descriptor = descriptor,
        operation = "repository.copy",
        extraBuild = {
          acceptForDescriptor(descriptor)
          if (resumeStart != null) {
            headers.append("Range", "bytes=$resumeStart-${descriptor.size - 1}")
          }
        },
        onSuccess = { response ->
          response.body<InputStream>().use { stream ->
            store.push(
              descriptor = descriptor,
              source = stream.source(),
              onProgress = { bytesOnDisk -> onBytes(bytesOnDisk) },
            )
          }
        },
      )
    return outcome == true
  }

  /** Probes the registry for RFC 7233 range support and caches the result. */
  @Volatile private var rangeSupported: Boolean? = null

  private suspend fun supportsRange(descriptor: Descriptor): Boolean {
    rangeSupported?.let {
      return it
    }
    val endpoint = router.blob(name, descriptor) ?: return false
    val result =
      httpWrapper.call(
        operation = "repository.supportsRange",
        buildRequest = {
          method = HttpMethod.Head
          url(endpoint)
          attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        },
        onSuccess = { res -> res.headers["Accept-Ranges"] == "bytes" },
      ) ?: false
    rangeSupported = result
    return result
  }

  /**
   * Routes [descriptor] to its registry endpoint by media type. Returns `null` without a digest.
   */
  private fun endpointFor(descriptor: Descriptor): Url? =
    when (descriptor.mediaType) {
      MANIFEST_MEDIA_TYPE,
      INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)

      else -> router.blob(name, descriptor)
    }

  /** Adds the right `Accept` header for manifest or index descriptors. No-op for blobs. */
  private fun HttpRequestBuilder.acceptForDescriptor(descriptor: Descriptor) {
    when (descriptor.mediaType) {
      MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
      INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
    }
  }

  /**
   * Shared builder for every descriptor-addressed request. Centralizes endpoint routing and the
   * pull scope; per-call differences (Accept, Range, body handling) come in through [extraBuild]
   * and [onSuccess].
   */
  private suspend fun <T> request(
    descriptor: Descriptor,
    operation: String,
    method: HttpMethod = HttpMethod.Get,
    extraBuild: HttpRequestBuilder.() -> Unit = {},
    onError: suspend (FailureResponse) -> T? = { null },
    onSuccess: suspend (HttpResponse) -> T?,
  ): T? {
    val endpoint = endpointFor(descriptor) ?: return null
    return httpWrapper.call(
      operation = operation,
      buildRequest = {
        this.method = method
        url(endpoint)
        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        extraBuild()
      },
      onError = onError,
      onSuccess = onSuccess,
    )
  }

  private companion object {
    private const val PROGRESS_COMPLETE = 100
  }
}
