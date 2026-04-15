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
import com.defenseunicorns.koci.api.LayoutMarker
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.OCIException
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
  val root: Path,
  internal val fileSystem: FileSystem,
  internal val dispatcher: CoroutineDispatcher,
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
        sink.writeUtf8(Json.encodeToString(LayoutMarker.serializer(), LayoutMarker("1.0.0")))
      }
    }

    index =
      if (fileSystem.exists(indexPath)) {
        fileSystem.source(indexPath).buffer().use { source ->
          Json.decodeFromString(source.readUtf8())
        }
      } else {
        Index()
      }

    // TODO: do this for all supported algorithms
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / "sha256")
    fileSystem.createDirectories(root / IMAGE_BLOBS_DIR / "sha512")
  }

  /**
   * Checks if a blob exists in the layout and verifies its integrity.
   *
   * Performs size and digest verification to ensure the content matches the descriptor's metadata.
   *
   * @param descriptor The descriptor of the blob to check
   * @throws OCIException.SizeMismatch if the blob's size doesn't match the descriptor
   * @throws OCIException.DigestMismatch if the blob's digest doesn't match the descriptor
   */
  suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
    val blobPath = blob(descriptor)

    val exists = withContext(dispatcher) { fileSystem.exists(blobPath) }
    if (!exists) return@runCatching false

    val size = withContext(dispatcher) { fileSystem.metadata(blobPath).size ?: 0L }
    if (size != descriptor.size) {
      throw OCIException.SizeMismatch(descriptor, size)
    }

    val digest =
      withContext(dispatcher) {
        val md = descriptor.digest.algorithm.hasher()
        fileSystem.source(blobPath).buffer().use { source ->
          val buf = Buffer()
          while (true) {
            val read = source.read(buf, HASH_BUFFER_SIZE)
            if (read == -1L) break
            md.update(buf.readByteArray())
          }
        }
        Digest(descriptor.digest.algorithm, md.digest())
      }

    if (digest != descriptor.digest) {
      throw OCIException.DigestMismatch(descriptor, digest)
    }

    true
  }

  /**
   * Pushes content to the layout from an Okio [Source].
   *
   * Writes the content to disk, verifying its size and digest match the descriptor. Supports
   * resumable uploads by calculating the digest of existing content plus new content.
   *
   * @param descriptor The descriptor of the content being pushed
   * @param source The source containing the content
   * @return Flow emitting the number of bytes written in each chunk
   * @throws OCIException.SizeMismatch if the final size doesn't match the descriptor
   * @throws OCIException.DigestMismatch if the final digest doesn't match the descriptor
   */
  @Suppress("detekt:CyclomaticComplexMethod")
  fun push(descriptor: Descriptor, source: Source): Flow<Int> = channelFlow {
    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }
    refCount.incrementAndGet()

    try {
      mu.withLock {
        val ok = exists(descriptor).getOrDefault(false)

        if (!ok) {
          val blobPath = blob(descriptor)
          val md = descriptor.digest.algorithm.hasher()

          // If resuming a download, start calculating the hash from the data on disk.
          // It is up to the caller to properly resume the source at the proper location,
          // otherwise a DigestMismatch will occur.
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

                send(data.size)
                yield()
              }
            }
          }

          val length = withContext(dispatcher) { fileSystem.metadata(blobPath).size ?: 0L }
          if (length != descriptor.size) {
            throw OCIException.SizeMismatch(descriptor, length)
          }

          val digest = Digest(descriptor.digest.algorithm, md.digest())
          if (digest != descriptor.digest) {
            withContext(dispatcher) { fileSystem.delete(blobPath) }
            throw OCIException.DigestMismatch(descriptor, digest)
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
   * Retrieves content from the layout as an Okio [Source].
   *
   * The caller is responsible for closing the returned source.
   *
   * @param descriptor The descriptor of the content to fetch
   */
  suspend fun fetch(descriptor: Descriptor): Source =
    withContext(dispatcher) { fileSystem.source(blob(descriptor)) }

  /**
   * Resolves a descriptor from the layout's index using a predicate function.
   *
   * @param predicate Function that returns true for the desired descriptor
   */
  suspend fun resolve(predicate: suspend (Descriptor) -> Boolean): Result<Descriptor> =
    runCatching {
      index.manifests.first { predicate(it) }
    }

  /**
   * Resolves a reference to a descriptor, optionally filtering by platform.
   *
   * @param reference The reference to resolve
   * @param platformResolver Optional function to select a specific platform
   */
  suspend fun resolve(
    reference: Reference,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Result<Descriptor> = resolve { desc ->
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
   * Associates a reference with a descriptor in the layout's index. If the reference already
   * exists, it will be reassigned to the new descriptor.
   *
   * @param descriptor The descriptor to tag
   * @param reference The reference to associate with the descriptor
   */
  suspend fun tag(descriptor: Descriptor, reference: Reference) = runCatching {
    require(descriptor.mediaType.isNotEmpty())
    require(descriptor.mediaType == MANIFEST_MEDIA_TYPE || descriptor.mediaType == INDEX_MEDIA_TYPE)
    require(descriptor.size > 0)
    reference.validate()

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
        prev.copy(annotations = prev.annotations?.minus("org.opencontainers.image.ref.name"))
    }

    index.manifests.add(copy)
    withContext(dispatcher) { syncIndex() }
  }

  /**
   * Removes a reference and its associated content from the layout.
   *
   * @param reference The reference to remove
   */
  suspend fun remove(reference: Reference): Result<Boolean> =
    resolve(reference).map { descriptor -> remove(descriptor).getOrThrow() }

  /**
   * Removes a descriptor and its associated content from the layout.
   *
   * For manifests and indexes, recursively removes all referenced content that isn't used by other
   * artifacts. Updates the index to remove references to the removed content.
   *
   * @param descriptor The descriptor to remove
   * @throws OCIException.UnableToRemove if the content is referenced by another artifact
   */
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
  suspend fun remove(descriptor: Descriptor): Result<Boolean> = runCatching {
    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }
    refCount.incrementAndGet()

    try {
      mu.withLock {
        val blobPath = blob(descriptor)
        val exists = withContext(dispatcher) { fileSystem.exists(blobPath) }

        if (!exists) return@runCatching true

        return when (descriptor.mediaType) {
          INDEX_MEDIA_TYPE -> {
            val indexToRemove: Index =
              fileSystem.source(blob(descriptor)).buffer().use { source ->
                Json.decodeFromString(source.readUtf8())
              }

            index.manifests.removeAll { it.digest == descriptor.digest }
            withContext(dispatcher) { syncIndex() }

            for (manifestDesc in indexToRemove.manifests) {
              remove(manifestDesc).getOrThrow()
            }

            withContext(dispatcher) { fileSystem.delete(blobPath) }
            Result.success(true)
          }

          MANIFEST_MEDIA_TYPE -> {
            val manifests = index.manifests.filter { it.digest != descriptor.digest }

            index.manifests.removeAll { it.digest == descriptor.digest }
            withContext(dispatcher) { syncIndex() }

            val allOtherLayers = expand(manifests)

            if (allOtherLayers.contains(descriptor)) {
              throw OCIException.UnableToRemove(
                descriptor,
                "manifest is referenced by another artifact",
              )
            }

            val manifest: Manifest =
              fileSystem.source(blob(descriptor)).buffer().use { source ->
                Json.decodeFromString(source.readUtf8())
              }

            (manifest.layers + manifest.config).forEach { layer ->
              if (!allOtherLayers.contains(layer)) {
                remove(layer).getOrThrow()
              }
            }

            withContext(dispatcher) { fileSystem.delete(blobPath) }
            Result.success(true)
          }

          else -> {
            withContext(dispatcher) { fileSystem.delete(blobPath) }
            Result.success(true)
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
   * This is a "stop the world" style function and MUST NOT run during any other operations. It
   * should be used to clean up zombie layers that might be left on disk if a remove operation is
   * interrupted.
   */
  suspend fun gc(): Result<List<Digest>> = runCatching {
    check(pushing.isEmpty()) { "there are downloads in progress" }

    val referencedDigests = expand(index.manifests).map { it.digest }.toSet()
    val blobsOnDisk = mutableListOf<Digest>()

    withContext(dispatcher) {
      for (algo in RegisteredAlgorithm.entries) {
        val algoDir = root / IMAGE_BLOBS_DIR / algo.toString()
        if (fileSystem.exists(algoDir)) {
          fileSystem.list(algoDir).forEach { path -> blobsOnDisk.add(Digest(algo, path.name)) }
        }
      }
    }

    withContext(dispatcher) {
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
      sink.writeUtf8(Json.encodeToString(index))
    }
  }

  /** Recursively expands descriptors to include all referenced content. */
  private suspend fun expand(descriptors: List<Descriptor>): Set<Descriptor> {
    return descriptors
      .flatMap { desc ->
        when (desc.mediaType) {
          INDEX_MEDIA_TYPE -> {
            if (withContext(dispatcher) { fileSystem.exists(blob(desc)) }) {
              val i: Index =
                fileSystem.source(blob(desc)).buffer().use { source ->
                  Json.decodeFromString(source.readUtf8())
                }
              listOf(desc) + i.manifests + expand(i.manifests)
            } else {
              emptyList()
            }
          }

          MANIFEST_MEDIA_TYPE -> {
            if (withContext(dispatcher) { fileSystem.exists(blob(desc)) }) {
              val m: Manifest =
                fileSystem.source(blob(desc)).buffer().use { source ->
                  Json.decodeFromString(source.readUtf8())
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

  private fun blob(descriptor: Descriptor): Path =
    root / IMAGE_BLOBS_DIR / descriptor.digest.algorithm.toString() / descriptor.digest.hex

  companion object {
    private const val HASH_BUFFER_SIZE = 32L * 1024
    private const val WRITE_BUFFER_SIZE = 4L * 1024
    private const val IMAGE_INDEX_FILE: String = "index.json"
    private const val IMAGE_BLOBS_DIR: String = "blobs"
    private const val IMAGE_LAYOUT_FILE: String = "oci-layout"
  }
}
