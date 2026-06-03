/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.internal.BlobState
import com.defenseunicorns.koci.internal.KociLogger
import com.defenseunicorns.koci.internal.LayoutMarker
import com.defenseunicorns.koci.internal.SizeConstants
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Source
import okio.blackholeSink
import okio.buffer

/**
 * On-disk OCI Image Layout. Stores blobs by content digest, tracks manifests in `index.json`, and
 * exposes lookup, tagging, and garbage collection.
 *
 * Construct once via [Koci.layout]; never instantiate directly. The directory layout follows the
 * OCI spec:
 * ```
 * {root}/
 *   oci-layout
 *   index.json
 *   blobs/
 *     sha256/{hex}
 *     sha512/{hex}
 * ```
 */
@Suppress("detekt:TooManyFunctions")
public class Layout
internal constructor(
  internal val root: Path,
  private val fileSystem: FileSystem,
  private val dispatcher: CoroutineDispatcher,
  private val json: Json,
  private val logger: KociLogger,
) {
  private lateinit var index: Index

  private val inflight = ConcurrentHashMap<Descriptor, Slot>()

  private val indexMutex = Mutex()

  private class Slot(val mu: Mutex, var refs: Int = 0)

  internal fun create() {
    val indexPath = root / IMAGE_INDEX_FILE
    val layoutFilePath = root / IMAGE_LAYOUT_FILE

    if (!fileSystem.exists(root)) {
      fileSystem.createDirectories(root)
    }

    if (!fileSystem.exists(layoutFilePath)) {
      fileSystem.sink(layoutFilePath).buffer().use { sink ->
        sink.writeUtf8(
          string =
            json.encodeToString(
              serializer = LayoutMarker.serializer(),
              LayoutMarker(imageLayoutVersion = "1.0.0"),
            )
        )
      }
    }

    index =
      when (fileSystem.exists(indexPath) && (fileSystem.metadata(indexPath).size ?: 0) > 0) {
        true ->
          fileSystem.source(indexPath).buffer().use { source ->
            json.decodeFromString(source.readUtf8())
          }

        false -> Index()
      }

    // TODO: #678 do this for all supported algorithms
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / "sha256")
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / "sha512")
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / IMAGE_BLOBS_TMP_DIR)
  }

  /**
   * Returns the on-disk state of [descriptor]'s blob: `Present` (size matches; correctness
   * guaranteed by write-time verification), `Partial` (temp file from an interrupted write), or
   * `Absent` (missing or corrupt).
   */
  internal suspend fun inspect(descriptor: Descriptor): BlobState =
    withDescriptorLock(descriptor) { inspectAcquiredLock(descriptor) }

  private suspend fun inspectAcquiredLock(descriptor: Descriptor): BlobState {
    val blobPath = blobPath(descriptor) ?: return BlobState.Absent
    val tmpPath = tmpBlobPath(descriptor) ?: return BlobState.Absent

    return withContext(dispatcher) {
      if (fileSystem.exists(blobPath)) {
        return@withContext when (fileSystem.metadata(blobPath).size ?: 0L) {
          descriptor.size -> BlobState.Present
          else -> BlobState.Absent
        }
      }
      if (fileSystem.exists(tmpPath)) {
        val tmpSize = fileSystem.metadata(tmpPath).size ?: 0L
        if (tmpSize in 1 until descriptor.size) return@withContext BlobState.Partial(tmpSize)
      }
      BlobState.Absent
    }
  }

  /**
   * Writes [descriptor]'s content from [source] into the layout. [onProgress] receives cumulative
   * bytes-on-disk (including any resume prefix) so callers can render `bytesOnDisk /
   * descriptor.size`. Content is streamed to a temp path, verified once the stream is drained, then
   * atomically renamed to the final blob path. A digest mismatch deletes the temp file and returns
   * `false`.
   *
   * If a temp file from a previous interrupted write exists, [source] is skipped past those bytes
   * and the remainder is appended.
   */
  @Suppress("detekt:CyclomaticComplexMethod", "detekt:LongMethod")
  internal suspend fun push(
    descriptor: Descriptor,
    source: Source,
    onProgress: (bytesOnDisk: Long) -> Unit = {},
  ): Boolean {
    val blobPath = blobPath(descriptor) ?: return false
    val tmpPath = tmpBlobPath(descriptor) ?: return false
    val expectedDigest = descriptor.digest ?: return false

    return withDescriptorLock(descriptor) {
      withContext(dispatcher) {
        val skip =
          when (val state = inspectAcquiredLock(descriptor)) {
            BlobState.Present -> return@withContext true
            BlobState.Absent -> 0L
            is BlobState.Partial -> state.bytesOnDisk
          }

        val bufferedSource = source.buffer()
        bufferedSource.skip(skip)

        // Fresh writes hash inline so verification needs no second read; resumed writes
        // can't hash the pre-existing prefix, so we re-read the temp file after appending.
        val hashingSource =
          when (skip) {
            0L ->
              when (expectedDigest.algorithm) {
                RegisteredAlgorithm.SHA256 -> HashingSource.sha256(bufferedSource)
                RegisteredAlgorithm.SHA512 -> HashingSource.sha512(bufferedSource)
              }

            else -> null
          }
        val readSource = hashingSource?.buffer() ?: bufferedSource

        if (skip > 0L) {
            fileSystem.appendingSink(tmpPath)
          } else {
            fileSystem.sink(tmpPath)
          }
          .buffer()
          .use { sink ->
            val chunk = Buffer()
            var written = skip
            while (true) {
              val read = readSource.read(chunk, SizeConstants.IO_BUFFER_SIZE)
              if (read == -1L) break
              sink.writeAll(chunk)
              written += read
              onProgress(written)
            }
          }

        when (hashingSource) {
          null -> {
            // Re-read the complete temp file to verify digest.
            val computed =
              fileSystem.source(tmpPath).buffer().use { src ->
                val hs =
                  when (expectedDigest.algorithm) {
                    RegisteredAlgorithm.SHA256 -> HashingSource.sha256(src)
                    RegisteredAlgorithm.SHA512 -> HashingSource.sha512(src)
                  }
                hs.buffer().use { it.readAll(blackholeSink()) }
                Digest(algorithm = expectedDigest.algorithm, hex = hs.hash.hex())
              }
            when (computed == expectedDigest) {
              true -> {
                fileSystem.atomicMove(tmpPath, blobPath)
                true
              }

              false -> {
                fileSystem.delete(tmpPath)
                false
              }
            }
          }

          else -> {
            val computed =
              Digest(algorithm = expectedDigest.algorithm, hex = hashingSource.hash.hex())
            when (computed == expectedDigest) {
              true -> {
                fileSystem.atomicMove(tmpPath, blobPath)
                true
              }

              false -> {
                fileSystem.delete(tmpPath)
                false
              }
            }
          }
        }
      }
    }
  }

  /** Returns a snapshot of the tagged manifest descriptors in the layout's `index.json`. */
  public fun catalog(): List<Descriptor> {
    return index.manifests.toList()
  }

  /**
   * Opens [descriptor]'s blob and passes it to [handler], or returns `null` if the blob is not
   * present. The source is closed when [handler] returns.
   */
  public suspend fun <T> fetchBlob(
    descriptor: Descriptor,
    handler: suspend (BufferedSource) -> T,
  ): T? {
    val blobPath = blobPath(descriptor) ?: return null
    return withDescriptorLock(descriptor) {
      withContext(dispatcher) {
        if (!fileSystem.exists(blobPath)) return@withContext null
        fileSystem.source(blobPath).buffer().use { source -> handler(source) }
      }
    }
  }

  /** Returns the first tagged descriptor matching [predicate], or `null` if none match. */
  public fun resolveDescriptor(predicate: (Descriptor) -> Boolean): Descriptor? {
    for (descriptor in index.manifests) {
      if (predicate(descriptor)) return descriptor
    }
    return null
  }

  /**
   * Returns the descriptor tagged with [reference], optionally filtered by [platformResolver].
   * Returns `null` if no tag matches.
   */
  public fun resolveReference(
    reference: Reference,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Descriptor? = resolveDescriptor { desc ->
    val refMatches = desc.annotations?.annotationRefName == reference.toString()
    val platformMatches =
      if (platformResolver != null && desc.platform != null) {
        platformResolver(desc.platform)
      } else {
        true
      }
    refMatches && platformMatches
  }

  /** Tags [descriptor] with [reference]. Callers are responsible for validating both inputs. */
  public suspend fun tag(descriptor: Descriptor, reference: Reference) {
    val copy =
      descriptor.copy(
        annotations =
          descriptor.annotations?.plus(ANNOTATION_REF_NAME to reference.toString())
            ?: mapOf(ANNOTATION_REF_NAME to reference.toString())
      )

    indexMutex.withLock {
      index.manifests.removeIf {
        it.annotations?.annotationRefName == reference.toString() &&
          it.platform == descriptor.platform
      }
      index.manifests.add(copy)
      withContext(dispatcher) { syncIndex() }
    }
  }

  /** Removes the tagged content for [reference]. Returns `true` if removed or already absent. */
  public suspend fun remove(reference: Reference): Boolean {
    val descriptor = resolveReference(reference) ?: return true
    return remove(descriptor)
  }

  /**
   * Removes [descriptor] and any of its referenced content that no other artifact still uses.
   * Returns `true` on success or when the blob was already absent, `false` when the descriptor (or
   * a nested descriptor for indexes) is still referenced elsewhere.
   */
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod", "detekt:ReturnCount")
  public suspend fun remove(descriptor: Descriptor): Boolean {
    val blobPath = blobPath(descriptor) ?: return true

    return withDescriptorLock(descriptor) {
      withContext(dispatcher) {
        if (!fileSystem.exists(blobPath)) {
          tmpBlobPath(descriptor)?.let { tmp -> if (fileSystem.exists(tmp)) fileSystem.delete(tmp) }
          return@withContext true
        }

        when (descriptor.mediaType) {
          OciConstants.INDEX_MEDIA_TYPE -> {
            val indexToRemove: Index =
              fileSystem.source(blobPath).buffer().use { source ->
                json.decodeFromString(source.readUtf8())
              }

            indexMutex.withLock {
              index.manifests.removeAll { it.digest == descriptor.digest }
              syncIndex()
            }

            for (manifestDesc in indexToRemove.manifests) {
              if (!remove(manifestDesc)) return@withContext false
            }

            fileSystem.delete(blobPath)
            true
          }

          OciConstants.MANIFEST_MEDIA_TYPE -> {
            val otherManifests =
              indexMutex.withLock {
                val others = index.manifests.filter { it.digest != descriptor.digest }
                index.manifests.removeAll { it.digest == descriptor.digest }
                syncIndex()
                others
              }

            val allOtherLayers = expand(otherManifests)

            if (allOtherLayers.contains(descriptor)) {
              logger.debug { "manifest $descriptor still referenced, skipping removal" }
              return@withContext false
            }

            val manifest: Manifest =
              fileSystem.source(blobPath).buffer().use { source ->
                json.decodeFromString(source.readUtf8())
              }

            (manifest.layers + manifest.config).forEach { layer ->
              if (!allOtherLayers.contains(layer)) {
                if (!remove(layer)) return@withContext false
              }
            }

            fileSystem.delete(blobPath)
            true
          }

          else -> {
            fileSystem.delete(blobPath)
            tmpBlobPath(descriptor)?.let { tmp ->
              if (fileSystem.exists(tmp)) fileSystem.delete(tmp)
            }
            true
          }
        }
      }
    }
  }

  /**
   * Deletes blobs on disk that no tagged manifest or index references. Returns the digests of
   * deleted blobs. Returns an empty list when pushes are in flight; the caller can retry once they
   * complete.
   */
  public suspend fun gc(): List<Digest> {
    if (inflight.isNotEmpty()) return emptyList()

    return withContext(dispatcher) {
      val referencedDigests = expand(index.manifests).mapNotNull { it.digest }.toSet()
      val blobsOnDisk = mutableListOf<Digest>()

      for (algo in RegisteredAlgorithm.entries) {
        val algoDir = root / IMAGE_BLOBS_DIR / algo.toString()
        if (fileSystem.exists(algoDir)) {
          fileSystem.list(algoDir).forEach { path ->
            Digest.parse("$algo:${path.name}")?.let { blobsOnDisk.add(it) }
          }
        }
      }

      val deleted =
        blobsOnDisk
          .filter { it !in referencedDigests }
          .mapNotNull { zombieDigest ->
            val path = root / IMAGE_BLOBS_DIR / zombieDigest.algorithm.toString() / zombieDigest.hex
            try {
              fileSystem.delete(path)
              zombieDigest
            } catch (_: Exception) {
              null
            }
          }

      val tmpDir = root / IMAGE_BLOBS_DIR / IMAGE_BLOBS_TMP_DIR
      if (fileSystem.exists(tmpDir)) {
        fileSystem.list(tmpDir).forEach { path ->
          try {
            fileSystem.delete(path)
          } catch (_: Exception) {}
        }
      }

      deleted
    }
  }

  private suspend fun <T> withDescriptorLock(descriptor: Descriptor, block: suspend () -> T): T {
    val slot =
      checkNotNull(
        inflight.compute(descriptor) { _, existing ->
          (existing ?: Slot(Mutex())).also { it.refs += 1 }
        }
      )
    try {
      return slot.mu.withLock { block() }
    } finally {
      inflight.compute(descriptor) { _, existing ->
        val s = existing ?: return@compute null
        s.refs -= 1
        if (s.refs <= 0) null else s
      }
    }
  }

  private fun syncIndex() {
    fileSystem.sink(root / IMAGE_INDEX_FILE).buffer().use { sink ->
      sink.writeUtf8(json.encodeToString(index))
    }
  }

  /** Recursively walks [descriptors] and returns every blob, manifest, and index they reference. */
  private fun expand(descriptors: List<Descriptor>): Set<Descriptor> {
    return descriptors
      .flatMap { desc ->
        val path = blobPath(desc)
        when (desc.mediaType) {
          OciConstants.INDEX_MEDIA_TYPE -> {
            if (path != null && fileSystem.exists(path)) {
              val i: Index =
                fileSystem.source(path).buffer().use { source ->
                  json.decodeFromString(source.readUtf8())
                }
              listOf(desc) + i.manifests + expand(i.manifests)
            } else {
              emptyList()
            }
          }

          OciConstants.MANIFEST_MEDIA_TYPE -> {
            if (path != null && fileSystem.exists(path)) {
              val m: Manifest =
                fileSystem.source(path).buffer().use { source ->
                  json.decodeFromString(source.readUtf8())
                }
              listOf(desc, m.config) + expand(m.layers)
            } else {
              emptyList()
            }
          }

          else -> listOf(desc)
        }
      }
      .toSet()
  }

  private fun blobPath(descriptor: Descriptor): Path? {
    val digest = descriptor.digest ?: return null
    return root / IMAGE_BLOBS_DIR / digest.algorithm.toString() / digest.hex
  }

  private fun tmpBlobPath(descriptor: Descriptor): Path? {
    val digest = descriptor.digest ?: return null
    return root / IMAGE_BLOBS_DIR / IMAGE_BLOBS_TMP_DIR / "${digest.algorithm}-${digest.hex}"
  }

  private companion object {
    private const val IMAGE_INDEX_FILE: String = "index.json"
    private const val IMAGE_BLOBS_DIR: String = "blobs"
    private const val IMAGE_LAYOUT_FILE: String = "oci-layout"
    private const val IMAGE_BLOBS_TMP_DIR: String = ".tmp"
  }
}
