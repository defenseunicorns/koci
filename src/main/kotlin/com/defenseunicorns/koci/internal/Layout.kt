/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.ANNOTATION_REF_NAME
import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Digest
import com.defenseunicorns.koci.api.Index
import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.Platform
import com.defenseunicorns.koci.api.Reference
import com.defenseunicorns.koci.api.RegisteredAlgorithm
import com.defenseunicorns.koci.api.annotationRefName
import com.defenseunicorns.koci.internal.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.internal.OciConstants.MANIFEST_MEDIA_TYPE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer

/**
 * OCI Image Layout backed by an Okio [FileSystem].
 *
 * Handles content-addressable blob storage, manifest/index management, reference tagging, and
 * garbage collection according to the OCI Image Layout specification.
 *
 * Constructed internally by [Koci] — consumers never interact with this class directly.
 * [com.defenseunicorns.koci.api.Repository] and other internal components access it via [Koci]'s
 * internal layout property.
 *
 * The directory structure on disk follows the OCI spec:
 * ```
 * {root}/
 *   ├── oci-layout
 *   ├── index.json
 *   └── blobs/
 *       ├── sha256/{hex}
 *       └── sha512/{hex}
 * ```
 */
@Suppress("detekt:TooManyFunctions")
internal class Layout(
  internal val root: Path,
  internal val fileSystem: FileSystem,
  internal val dispatcher: CoroutineDispatcher,
  internal val json: Json,
) {
  internal val index: Index
  internal val pushing = ConcurrentHashMap<Descriptor, Pair<Mutex, AtomicInteger>>()

  init {
    val indexPath = root / IMAGE_INDEX_FILE
    val layoutFilePath = root / IMAGE_LAYOUT_FILE

    if (!fileSystem.exists(root)) {
      fileSystem.createDirectories(root)
    }

    if (!fileSystem.exists(layoutFilePath)) {
      fileSystem.sink(layoutFilePath).buffer().use { sink ->
        sink.writeUtf8(json.encodeToString(LayoutMarker.serializer(), LayoutMarker("1.0.0")))
      }
    }

    index =
      if (fileSystem.exists(indexPath)) {
        fileSystem.source(indexPath).buffer().use { source ->
          json.decodeFromString(source.readUtf8())
        }
      } else {
        Index()
      }

    // TODO: MOBILE-219 do this for all supported algorithms
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / "sha256")
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / "sha512")
  }

  /**
   * Returns true iff the blob is on disk and verifies (size and digest match the descriptor).
   *
   * False covers absent, partially-downloaded, and corrupted-content cases. Callers who need to
   * distinguish "partial — can resume" from "absent or bad" use [partialBytesOnDisk].
   */
  suspend fun exists(descriptor: Descriptor): Boolean {
    val blobPath = blob(descriptor) ?: return false
    val expectedDigest = descriptor.digest ?: return false

    if (!withContext(dispatcher) { fileSystem.exists(blobPath) }) return false

    val size = withContext(dispatcher) { fileSystem.metadata(blobPath).size ?: 0L }
    if (size != descriptor.size) return false

    val digest =
      withContext(dispatcher) {
        val md = expectedDigest.algorithm.hasher()
        fileSystem.source(blobPath).buffer().use { source ->
          val buf = Buffer()
          while (true) {
            val read = source.read(buf, HASH_BUFFER_SIZE)
            if (read == -1L) break
            md.update(buf.readByteArray())
          }
        }
        Digest(expectedDigest.algorithm, md.digest())
      }

    return digest == expectedDigest
  }

  /**
   * If a partial blob exists on disk that can serve as a resume prefix for [descriptor], returns
   * its on-disk size; otherwise null.
   *
   * Returns null when the blob is absent, fully present, or has size >= the descriptor's size. Used
   * by `Repository.copy` to decide whether to issue a `Range` request — the only consumer.
   */
  suspend fun partialBytesOnDisk(descriptor: Descriptor): Long? {
    val blobPath = blob(descriptor) ?: return null
    if (!withContext(dispatcher) { fileSystem.exists(blobPath) }) return null
    val size = withContext(dispatcher) { fileSystem.metadata(blobPath).size ?: 0L }
    return if (size in 1 until descriptor.size) size else null
  }

  /**
   * Pushes content to the layout from an Okio [Source].
   *
   * Writes the content to disk, verifying its size and digest match the descriptor. Emits
   * [PushEvent.Progress] per chunk; the terminal event is [PushEvent.Completed] on success or
   * [PushEvent.Failed] otherwise (size or digest mismatch logged inside before the terminal event).
   */
  @Suppress("detekt:CyclomaticComplexMethod", "detekt:LongMethod")
  fun push(descriptor: Descriptor, source: Source): Flow<PushEvent> = channelFlow {
    val expectedDigest = descriptor.digest ?: return@channelFlow
    val blobPath = blob(descriptor) ?: return@channelFlow

    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }
    refCount.incrementAndGet()

    try {
      mu.withLock {
        if (exists(descriptor)) {
          send(PushEvent.Completed)
          return@withLock
        }

        val md = expectedDigest.algorithm.hasher()

        // If resuming a download, start calculating the hash from the data on disk.
        // It is up to the caller to properly resume the source at the proper location,
        // otherwise a digest mismatch will occur and we'll log + emit Failed.
        val blobExists = withContext(dispatcher) { fileSystem.exists(blobPath) }
        if (blobExists) {
          withContext(dispatcher) {
            fileSystem.source(blobPath).buffer().use { existing ->
              val buf = Buffer()
              while (true) {
                val read = existing.read(buf, HASH_BUFFER_SIZE)
                if (read == -1L) break
                md.update(buf.readByteArray())
              }
            }
          }
        }

        withContext(dispatcher) {
          fileSystem.appendingSink(blobPath).buffer().use { sink ->
            val buf = Buffer()
            while (true) {
              val bytesRead = source.read(buf, WRITE_BUFFER_SIZE)
              if (bytesRead == -1L) break
              val data = buf.readByteArray()
              md.update(data)
              sink.write(data)
              sink.flush()

              send(PushEvent.Progress(data.size))
              yield()
            }
          }
        }

        val length = withContext(dispatcher) { fileSystem.metadata(blobPath).size ?: 0L }
        if (length != descriptor.size) {
          // TODO: MOBILE-198 - Log size mismatch — expected=${descriptor.size}, actual=$length,
          // descriptor=$descriptor
          send(PushEvent.Failed)
          return@withLock
        }

        val digest = Digest(expectedDigest.algorithm, md.digest())
        if (digest != expectedDigest) {
          withContext(dispatcher) { fileSystem.delete(blobPath) }
          // TODO: MOBILE-198 - Log digest mismatch — expected=$expectedDigest, actual=$digest,
          // descriptor=$descriptor
          send(PushEvent.Failed)
          return@withLock
        }

        send(PushEvent.Completed)
      }
    } finally {
      val pair = pushing[descriptor]
      if (pair != null) {
        val count = pair.second.decrementAndGet()
        if (count <= 0) {
          pushing.remove(descriptor, pair)
        }
      }
    }
  }

  /**
   * Retrieves content from the layout as an Okio [Source], or null if [descriptor] has no digest.
   *
   * The caller is responsible for closing the returned source.
   */
  suspend fun fetch(descriptor: Descriptor): Source? {
    val blobPath = blob(descriptor) ?: return null
    return withContext(dispatcher) { fileSystem.source(blobPath) }
  }

  /** Resolves a descriptor from the layout's index using a predicate; null if no match. */
  suspend fun resolve(predicate: suspend (Descriptor) -> Boolean): Descriptor? {
    for (manifest in index.manifests) {
      if (predicate(manifest)) return manifest
    }
    return null
  }

  /** Resolves a reference to a descriptor, optionally filtering by platform; null if no match. */
  suspend fun resolve(
    reference: Reference,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Descriptor? = resolve { desc ->
    val refMatches = desc.annotations?.annotationRefName == reference.toString()
    val platformMatches =
      if (platformResolver != null && desc.platform != null) {
        platformResolver(desc.platform)
      } else {
        true
      }
    refMatches && platformMatches
  }

  /**
   * Tags a descriptor with a reference.
   *
   * Internal contract: callers pass descriptors fetched from a registry and references built from
   * trusted components, so no validation happens here.
   */
  suspend fun tag(descriptor: Descriptor, reference: Reference) {
    val copy =
      descriptor.copy(
        annotations =
          descriptor.annotations?.plus(ANNOTATION_REF_NAME to reference.toString())
            ?: mapOf(ANNOTATION_REF_NAME to reference.toString())
      )

    // untag the first manifest w/ this exact ref, there should only be one
    val prevIndex =
      index.manifests.indexOfFirst {
        it.annotations?.annotationRefName == reference.toString() &&
          it.platform == descriptor.platform
      }
    if (prevIndex != -1) {
      val prev = index.manifests[prevIndex]
      index.manifests[prevIndex] =
        prev.copy(annotations = prev.annotations?.minus(ANNOTATION_REF_NAME))
    }

    index.manifests.add(copy)
    withContext(dispatcher) { syncIndex() }
  }

  /** Removes a reference and its associated content from the layout. */
  suspend fun remove(reference: Reference): Boolean {
    val descriptor = resolve(reference) ?: return true
    return remove(descriptor)
  }

  /**
   * Removes a descriptor and its associated content from the layout.
   *
   * For manifests and indexes, recursively removes all referenced content that isn't used by other
   * artifacts. Returns true on successful removal (or when the blob was already absent). Returns
   * false when the descriptor (or any nested descriptor for indexes) is still referenced by another
   * artifact; the still-referenced descriptor is logged.
   */
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod", "detekt:ReturnCount")
  suspend fun remove(descriptor: Descriptor): Boolean {
    val blobPath = blob(descriptor) ?: return true

    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }
    refCount.incrementAndGet()

    try {
      mu.withLock {
        val exists = withContext(dispatcher) { fileSystem.exists(blobPath) }
        if (!exists) return true

        return when (descriptor.mediaType) {
          INDEX_MEDIA_TYPE -> {
            val indexToRemove: Index =
              fileSystem.source(blobPath).buffer().use { source ->
                json.decodeFromString(source.readUtf8())
              }

            index.manifests.removeAll { it.digest == descriptor.digest }
            withContext(dispatcher) { syncIndex() }

            for (manifestDesc in indexToRemove.manifests) {
              if (!remove(manifestDesc)) return false
            }

            withContext(dispatcher) { fileSystem.delete(blobPath) }
            true
          }

          MANIFEST_MEDIA_TYPE -> {
            val otherManifests = index.manifests.filter { it.digest != descriptor.digest }

            index.manifests.removeAll { it.digest == descriptor.digest }
            withContext(dispatcher) { syncIndex() }

            val allOtherLayers = expand(otherManifests)

            if (allOtherLayers.contains(descriptor)) {
              // TODO: MOBILE-198 - Log "manifest still referenced by another artifact: $descriptor"
              return false
            }

            val manifest: Manifest =
              fileSystem.source(blobPath).buffer().use { source ->
                json.decodeFromString(source.readUtf8())
              }

            (manifest.layers + manifest.config).forEach { layer ->
              if (!allOtherLayers.contains(layer)) {
                if (!remove(layer)) return false
              }
            }

            withContext(dispatcher) { fileSystem.delete(blobPath) }
            true
          }

          else -> {
            withContext(dispatcher) { fileSystem.delete(blobPath) }
            true
          }
        }
      }
    } finally {
      val pair = pushing[descriptor]
      if (pair != null) {
        val count = pair.second.decrementAndGet()
        if (count <= 0) {
          pushing.remove(descriptor, pair)
        }
      }
    }
  }

  /**
   * Lists all manifests in the layout.
   *
   * @return List of all manifest descriptors in the layout's index
   */
  fun catalog(): List<Descriptor> = index.manifests.toList()

  /**
   * Prunes all layers on disk that are not referenced by any manifest or index in the layout's
   * index.
   *
   * Must not run during any other layout operations. If called while pushes are in flight, returns
   * an empty list rather than proceeding with a racy scan — caller can retry once pushes complete.
   */
  suspend fun gc(): List<Digest> {
    if (pushing.isNotEmpty()) return emptyList()

    val referencedDigests = expand(index.manifests).mapNotNull { it.digest }.toSet()
    val blobsOnDisk = mutableListOf<Digest>()

    withContext(dispatcher) {
      for (algo in RegisteredAlgorithm.entries) {
        val algoDir = root / IMAGE_BLOBS_DIR / algo.toString()
        if (fileSystem.exists(algoDir)) {
          fileSystem.list(algoDir).forEach { path ->
            Digest.parse("$algo:${path.name}")?.let { blobsOnDisk.add(it) }
          }
        }
      }
    }

    return withContext(dispatcher) {
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
    }
  }

  /** Synchronizes the in-memory index to disk. */
  internal fun syncIndex() {
    fileSystem.sink(root / IMAGE_INDEX_FILE).buffer().use { sink ->
      sink.writeUtf8(json.encodeToString(index))
    }
  }

  /** Recursively expands descriptors to include all referenced content. */
  private suspend fun expand(descriptors: List<Descriptor>): Set<Descriptor> {
    return descriptors
      .flatMap { desc ->
        val path = blob(desc)
        when (desc.mediaType) {
          INDEX_MEDIA_TYPE -> {
            if (path != null && withContext(dispatcher) { fileSystem.exists(path) }) {
              val i: Index =
                fileSystem.source(path).buffer().use { source ->
                  json.decodeFromString(source.readUtf8())
                }
              listOf(desc) + i.manifests + expand(i.manifests)
            } else {
              emptyList()
            }
          }

          MANIFEST_MEDIA_TYPE -> {
            if (path != null && withContext(dispatcher) { fileSystem.exists(path) }) {
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

  private fun blob(descriptor: Descriptor): Path? {
    val digest = descriptor.digest ?: return null
    return root / IMAGE_BLOBS_DIR / digest.algorithm.toString() / digest.hex
  }

  companion object {
    private const val HASH_BUFFER_SIZE = 32L * 1024
    private const val WRITE_BUFFER_SIZE = 4L * 1024
    private const val IMAGE_INDEX_FILE: String = "index.json"
    private const val IMAGE_BLOBS_DIR: String = "blobs"
    private const val IMAGE_LAYOUT_FILE: String = "oci-layout"
  }
}
