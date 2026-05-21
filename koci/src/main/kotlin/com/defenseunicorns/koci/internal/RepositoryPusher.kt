/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Digest
import com.defenseunicorns.koci.api.Index
import com.defenseunicorns.koci.api.Layout
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.api.OciConstants.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.api.Reference
import com.defenseunicorns.koci.api.RegisteredAlgorithm
import com.defenseunicorns.koci.api.TransferEvent
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.internal.Regex.tagRegex
import com.defenseunicorns.koci.internal.SizeConstants.DEFAULT_PUSH_CHUNK_SIZE
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.source

/**
 * Push side of a [Repository]. Two entry points exist: [push] with a stream uploads a single blob,
 * and [push] with a root descriptor mirrors [RepositoryPuller.pull] by walking the local layout,
 * uploading every referenced blob, and PUT-ing each manifest or index in post-order so the root
 * only lands after its children. Both routes delegate the actual byte transfer to [pushBlob].
 *
 * The wire flow follows the OCI distribution spec:
 * 1. HEAD `/blobs/<digest>`. A 200 short-circuits if the blob is already on the remote.
 * 2. POST `/blobs/uploads/` opens an upload session (or GET resumes one).
 * 3. Either PUT the session location with `?digest=<digest>` and the full body (monolithic), or
 *    PATCH per chunk with `Content-Range` and finish with an empty PUT carrying `?digest=<digest>`
 *    (chunked + commit).
 * 4. PUT `/manifests/<ref>` to publish each manifest or index, where `<ref>` is a digest or tag.
 */
internal class RepositoryPusher(
  private val name: String,
  private val caller: HttpWrapper,
  private val router: Router,
  private val store: Layout,
  private val json: Json,
  private val pushConfig: PushConfig,
  private val puller: RepositoryPuller,
  private val logger: KociLogger,
) {

  /** Active upload sessions, keyed by descriptor, so partial uploads can resume. */
  private val uploading = ConcurrentHashMap<Descriptor, UploadStatus>()

  /**
   * Pushes a single blob. Skips via HEAD when the blob is already on the remote; otherwise opens an
   * upload session and dispatches to monolithic or chunked. Emits [TransferEvent.Progress] as bytes
   * flow and ends with `Progress(100)` on success or [TransferEvent.Failed] on failure.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs">OCI
   *   Distribution Spec: Pushing Blobs</a>
   *
   * TODO: MOBILE-210 replace [TransferEvent] with a unified progress type.
   */
  fun push(stream: InputStream, expected: Descriptor): Flow<TransferEvent> =
    channelFlow {
        val total = expected.size
        val ok =
          stream.use { s ->
            pushBlob(s.source().buffer(), expected) { bytes ->
              trySend(TransferEvent.Progress((bytes * PROGRESS_COMPLETE / total).toInt()))
            }
          }
        when (ok) {
          true -> send(TransferEvent.Progress(PROGRESS_COMPLETE))
          false -> send(TransferEvent.Failed)
        }
      }
      .distinctUntilChanged()
      .onCompletion { cause -> if (cause == null) uploading.remove(expected) }

  /**
   * Pushes [root] and everything it references from the local layout. When [tag] is non-null the
   * root is registered under that name on the remote and the local `index.json` is tagged
   * identically. An invalid OCI tag fails the flow immediately; without a tag the root is published
   * by digest only.
   */
  fun push(root: Descriptor, tag: String? = null): Flow<TransferEvent> =
    flow {
        if (tag != null && tagRegex.matchEntire(tag) == null) {
          logger.warn { "invalid tag '$tag' rejected before push" }
          emit(TransferEvent.Failed)
          return@flow
        }
        when (val walk = walkTree(root, json, logger) { readContainer(it) }) {
          null -> emit(TransferEvent.Failed)
          else -> execute(walk, tag).collect { emit(it) }
        }
      }
      .distinctUntilChanged()

  /**
   * Tags a [Manifest] under [ref].
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests">OCI
   *   Distribution Spec: Pushing Manifests</a>
   */
  suspend fun tag(content: Manifest, ref: String): Descriptor? =
    tagContent(ref, content.mediaType ?: MANIFEST_MEDIA_TYPE, json.encodeToString(content))

  /**
   * Tags an [Index] under [ref].
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests">OCI
   *   Distribution Spec: Pushing Manifests</a>
   */
  suspend fun tag(content: Index, ref: String): Descriptor? =
    tagContent(ref, content.mediaType ?: INDEX_MEDIA_TYPE, json.encodeToString(content))

  private suspend fun tagContent(ref: String, mediaType: String, body: String): Descriptor? {
    if (tagRegex.matchEntire(ref) == null) return null
    val bodyBytes = body.toByteArray(Charsets.UTF_8)
    if (!putManifest(mediaType, bodyBytes, ref)) return null
    val hash = RegisteredAlgorithm.SHA256.hasher().digest(bodyBytes)
    return Descriptor(
      mediaType = mediaType,
      digest = Digest(algorithm = RegisteredAlgorithm.SHA256, hex = hash),
      size = bodyBytes.size.toLong(),
    )
  }

  /**
   * Uploads one blob. Returns `true` on success, `false` on any wire-level failure. [onBytes]
   * reports cumulative bytes uploaded, including the full size when the blob is already on the
   * remote, so batched callers can aggregate progress. Does not close [source]; the caller owns its
   * lifecycle.
   */
  private suspend fun pushBlob(
    source: BufferedSource,
    expected: Descriptor,
    onBytes: (Long) -> Unit,
  ): Boolean {
    if (puller.exists(expected)) {
      onBytes(expected.size)
      return true
    }

    val rawSession = startOrResumeUpload(expected) ?: return false
    val session =
      when (rawSession.minChunkSize) {
        0L -> rawSession.copy(minChunkSize = pushConfig.minChunkSize ?: DEFAULT_PUSH_CHUNK_SIZE)
        else -> rawSession
      }
    uploading[expected] = session

    return uploadBlob(source, expected, session, onBytes)
  }

  /**
   * Uploads every blob concurrently (bounded by [PushConfig.concurrency]), then PUTs each cached
   * container in post-order so the root lands last. The root publishes under [tag] when provided or
   * its digest otherwise; on success the local layout is tagged so subsequent local lookups by name
   * resolve.
   */
  private fun execute(walk: TreeWalk, tag: String?): Flow<TransferEvent> =
    channelFlow {
        val total = walk.totalBytes
        if (total == 0L) {
          send(TransferEvent.Progress(PROGRESS_COMPLETE))
          return@channelFlow
        }

        val tracker = ProgressTracker(total)
        val emitJob = launch { for (pct in tracker.channel) send(TransferEvent.Progress(pct)) }

        val blobsOk =
          walk.dispatchBlobs(pushConfig.concurrency) { blob ->
            store.fetchBlob(blob) { source ->
              pushBlob(source, blob) { bytes -> tracker.update(blob, bytes) }
            } == true
          }
        if (!blobsOk) {
          tracker.close()
          emitJob.join()
          send(TransferEvent.Failed)
          return@channelFlow
        }

        val rootIndex = walk.containers.size - 1
        for ((idx, pair) in walk.containers.withIndex()) {
          val (descriptor, buffer) = pair
          val ref =
            when {
              idx == rootIndex && tag != null -> tag
              else -> descriptor.digest.toString()
            }
          if (!putManifest(descriptor.mediaType, buffer.snapshot().toByteArray(), ref)) {
            tracker.close()
            emitJob.join()
            send(TransferEvent.Failed)
            return@channelFlow
          }
          tracker.update(descriptor, descriptor.size)
        }

        if (tag != null) {
          val root = walk.containers.last().first
          store.tag(root, Reference(registry = router.base(), repository = name, reference = tag))
        }

        tracker.close()
        emitJob.join()
        send(TransferEvent.Progress(PROGRESS_COMPLETE))
      }
      .onCompletion { cause ->
        if (cause == null) {
          walk.blobs.forEach { uploading.remove(it) }
        }
      }

  /** Reads a manifest or index body out of the layout into a buffer. */
  private suspend fun readContainer(descriptor: Descriptor): Buffer? =
    store.fetchBlob(descriptor) { source -> Buffer().apply { writeAll(source) } }

  /**
   * PUTs a manifest or index to `/manifests/<ref>` where `<ref>` is a tag (for the tagged root) or
   * the descriptor's digest (for non-root containers and untagged roots). Returns `true` on `201
   * Created`.
   */
  private suspend fun putManifest(mediaType: String, body: ByteArray, ref: String): Boolean {
    val status =
      caller.call(
        operation = "repository.push.manifest",
        buildRequest = {
          method = HttpMethod.Put
          url(router.manifest(name, ref))
          contentType(ContentType.parse(mediaType))
          setBody(body)
          attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
        },
        onSuccess = { res -> res.status },
      )
    return status == HttpStatusCode.Created
  }

  /**
   * Picks monolithic upload when the remaining body fits in one chunk, chunked upload otherwise.
   * Returns `true` on success.
   */
  private suspend fun uploadBlob(
    source: BufferedSource,
    expected: Descriptor,
    session: UploadStatus,
    onBytes: (Long) -> Unit,
  ): Boolean {
    val bytesLeft = expected.size - session.offset
    return when (bytesLeft) {
      in 1..session.minChunkSize -> uploadMonolithic(source, expected, session)
      else -> uploadChunked(source, expected, session, onBytes)
    }
  }

  /**
   * Single PUT to the upload session with `?digest=<digest>` and the full body. Expects `201
   * Created`.
   */
  private suspend fun uploadMonolithic(
    source: BufferedSource,
    expected: Descriptor,
    session: UploadStatus,
  ): Boolean {
    val bytesLeft = expected.size - session.offset
    if (session.offset > 0L) source.skip(session.offset)
    val status =
      caller.call(
        operation = "repository.push.monolithic",
        buildRequest = {
          method = HttpMethod.Put
          url(session.location)
          url { parameters.append("digest", expected.digest.toString()) }
          headers { append(HttpHeaders.ContentLength, bytesLeft.toString()) }
          setBody(source.inputStream())
          attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
        },
        onSuccess = { res -> res.status },
      )
    return status == HttpStatusCode.Created
  }

  /**
   * Chunked upload. Skips past any already-committed prefix, PATCHes each chunk with
   * `Content-Range` (expecting `202 Accepted` + updated session), then commits with a final PUT
   * carrying `?digest=<digest>`. Reports cumulative bytes via [onBytes] after every accepted chunk.
   * Each chunk streams via [LimitedSource], so memory stays at O(socket buffer).
   */
  private suspend fun uploadChunked(
    source: BufferedSource,
    expected: Descriptor,
    session: UploadStatus,
    onBytes: (Long) -> Unit,
  ): Boolean =
    withContext(Dispatchers.IO) {
      // Pin minChunkSize from the initial session: PATCH responses typically omit
      // OCI-Chunk-Min-Length (it's a session-level capability advertised once on POST), and
      // toUploadStatus parses a missing header as 0. Letting that overwrite the session would
      // shrink chunkSize to 0 and exit the loop after one chunk.
      val chunkSize = session.minChunkSize
      var currentLocation = session.location
      var offset = session.offset
      if (offset > 0L) source.skip(offset + 1)

      while (currentCoroutineContext().isActive) {
        val want = minOf(expected.size - offset, chunkSize)
        if (want <= 0L) break

        val updated = pushChunk(currentLocation, source, want, offset) ?: return@withContext false
        currentLocation = updated.location
        uploading[expected] = updated.copy(minChunkSize = chunkSize)
        offset = updated.offset + 1
        onBytes(offset)
      }

      commitUpload(currentLocation, expected)
    }

  /**
   * One PATCH against the upload session with `Content-Range: $offset-$endRange`. Returns the
   * updated [UploadStatus] on `202 Accepted`, or `null` otherwise. Wraps [source] in a
   * [LimitedSource] so OkHttp reads exactly [chunkSize] bytes straight to the socket.
   */
  private suspend fun pushChunk(
    location: String,
    source: BufferedSource,
    chunkSize: Long,
    offset: Long,
  ): UploadStatus? {
    val endRange = offset + chunkSize - 1
    return caller.call(
      operation = "repository.push.chunk",
      buildRequest = {
        method = HttpMethod.Patch
        url(router.parseUploadLocation(location))
        contentType(ContentType.Application.OctetStream)
        headers {
          append(HttpHeaders.ContentLength, chunkSize.toString())
          append(HttpHeaders.ContentRange, "$offset-$endRange")
        }
        setBody(LimitedSource(source, chunkSize).buffer().inputStream())
        attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
      },
      onSuccess = { res ->
        when (res.status) {
          HttpStatusCode.Accepted -> res.headers.toUploadStatus()
          else -> {
            logger.warn { "unexpected status ${res.status} from chunked upload PATCH" }
            null
          }
        }
      },
    )
  }

  /**
   * Final PUT with `?digest=<digest>` and an empty body to close a chunked upload. Expects `201
   * Created`.
   */
  private suspend fun commitUpload(location: String, expected: Descriptor): Boolean {
    val status =
      caller.call(
        operation = "repository.push.commit",
        buildRequest = {
          method = HttpMethod.Put
          url(router.parseUploadLocation(location))
          url { encodedParameters.append("digest", expected.digest.toString()) }
          attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
        },
        onSuccess = { res -> res.status },
        onError = {
          logger.warn { "commit upload failed: $it" }
          null
        },
      )
    return status == HttpStatusCode.Created
  }

  /**
   * Opens a new upload session via POST, or refreshes the offset of an existing session via GET.
   * Returns `null` on any unexpected status. A `404` on the existing session means it has expired
   * server-side; the cached entry is dropped and a fresh session is opened.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#starting-an-upload">OCI
   *   Distribution Spec: Starting an Upload</a>
   */
  @Suppress("detekt:ReturnCount")
  private suspend fun startOrResumeUpload(descriptor: Descriptor): UploadStatus? {
    val prev =
      uploading[descriptor]
        ?: return caller.call(
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
            val synced = prev.copy(offset = curr.offset)
            uploading[descriptor] = synced
            return synced
          }
        }

        else -> return null
      }
    }

    return prev
  }

  private companion object {
    private const val PROGRESS_COMPLETE = 100
  }

  /**
   * Forwards reads from [delegate] but caps them at [remaining] bytes, returning `-1` once
   * exhausted. Used in [pushChunk] to stream one chunk straight from the shared blob source.
   */
  private class LimitedSource(delegate: Source, private var remaining: Long) :
    ForwardingSource(delegate) {
    override fun read(sink: Buffer, byteCount: Long): Long {
      if (remaining <= 0L) return -1L
      val n = super.read(sink, minOf(byteCount, remaining))
      if (n > 0L) remaining -= n
      return n
    }

    override fun close() {
      // Intentionally a no-op so Ktor closing the per-PATCH InputStream does not cascade
      // down and close the shared delegate between chunks.
    }
  }
}
