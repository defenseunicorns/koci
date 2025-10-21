/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.models.ANNOTATION_REF_NAME
import com.defenseunicorns.koci.models.Descriptor
import com.defenseunicorns.koci.models.Digest
import com.defenseunicorns.koci.models.IMAGE_BLOBS_DIR
import com.defenseunicorns.koci.models.IMAGE_INDEX_FILE
import com.defenseunicorns.koci.models.IMAGE_LAYOUT_FILE
import com.defenseunicorns.koci.models.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.models.Index
import com.defenseunicorns.koci.models.LayoutMarker
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.Manifest
import com.defenseunicorns.koci.models.OCIException
import com.defenseunicorns.koci.models.Platform
import com.defenseunicorns.koci.models.Reference
import com.defenseunicorns.koci.models.RegisteredAlgorithm
import com.defenseunicorns.koci.models.annotationRefName
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

/**
 * Implements an OCI Image Layout for storing and managing container images locally.
 *
 * The Layout class provides functionality for storing, retrieving, and managing OCI artifacts on
 * disk according to the OCI Image Layout specification. It handles:
 * - Content-addressable blob storage
 * - Content verification using size and digest
 * - Manifest and index management
 * - Reference tagging
 * - Garbage collection
 *
 * @property index The index of all manifests in this layout
 * @property root The root directory path where the layout is stored
 */
@Suppress("detekt:TooManyFunctions")
class Layout private constructor(internal val index: Index, private val root: String) {
  internal val pushing = ConcurrentHashMap<Descriptor, Pair<Mutex, AtomicInteger>>()

  companion object {
    /**
     * Creates a new Layout or opens an existing one at the specified path.
     *
     * If the directory doesn't exist, it will be created along with the necessary structure for an
     * OCI Image Layout. If it exists, the existing layout will be loaded.
     *
     * @param root The root directory path for the layout
     */
    suspend fun create(root: String): Result<Layout> =
      withContext(Dispatchers.IO) {
        runCatching {
          var index = Index()
          val indexLocation = "$root/$IMAGE_INDEX_FILE"
          val layoutFileLocation = "$root/$IMAGE_LAYOUT_FILE"

          val rootDir = File(root)
          if (!rootDir.exists()) {
            rootDir.mkdirs()
            File(layoutFileLocation).writeText(Json.encodeToString(LayoutMarker("1.0.0")))
          } else {
            require(rootDir.isDirectory) { "$root must be an existing directory" }
            // TODO: handle oci-layout version checking
            if (!File(layoutFileLocation).exists()) {
              File(layoutFileLocation).writeText(Json.encodeToString(LayoutMarker("1.0.0")))
            }
          }

          if (File(indexLocation).exists()) {
            index = Json.decodeFromString(File(indexLocation).readText())
          }

          // TODO: do this for all supported algorithms
          File("$root/$IMAGE_BLOBS_DIR/sha256").mkdirs()
          File("$root/$IMAGE_BLOBS_DIR/sha512").mkdirs()

          Layout(index, root)
        }
      }
  }

  /**
   * Checks if a blob exists in the layout and verifies its integrity.
   *
   * Performs size and digest verification to ensure the content matches the descriptor's metadata.
   *
   * @param descriptor The descriptor of the blob to check
   * @throws com.defenseunicorns.koci.models.OCIException.SizeMismatch if the blob's size doesn't match the descriptor
   * @throws com.defenseunicorns.koci.models.OCIException.DigestMismatch if the blob's digest doesn't match the descriptor
   */
  suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
    val file = blob(descriptor)

    val exists = withContext(Dispatchers.IO) { file.exists() }
    if (!exists) {
      return@runCatching false
    }

    val length = withContext(Dispatchers.IO) { file.length() }
    if (length != descriptor.size) {
      throw OCIException.SizeMismatch(descriptor, length)
    }

    val digest =
      withContext(Dispatchers.IO) {
        file.inputStream().use { s ->
          val buffer = ByteArray(1024)
          val md = descriptor.digest.algorithm.hasher()
          var bytesRead: Int
          while (s.read(buffer).also { bytesRead = it } != -1) {
            md.update(buffer, 0, bytesRead)
          }
          Digest(descriptor.digest.algorithm, md.digest())
        }
      }
    if (digest != descriptor.digest) {
      throw OCIException.DigestMismatch(descriptor, digest)
    }

    true
  }

  /**
   * Recursively expands a list of descriptors to include all referenced content.
   *
   * For manifests, includes the config and all layers. For indexes, includes all referenced
   * manifests and their contents.
   *
   * @param descriptors List of descriptors to expand
   */
  @OptIn(ExperimentalSerializationApi::class)
  private suspend fun expand(descriptors: List<Descriptor>): Set<Descriptor> {
    return descriptors
      .flatMap { desc ->
        when (desc.mediaType) {
          INDEX_MEDIA_TYPE -> {
            if (withContext(Dispatchers.IO) { blob(desc).exists() }) {
              val i: Index = fetch(desc).use { Json.decodeFromStream(it) }
              listOf(desc) + i.manifests + expand(i.manifests)
            } else {
              emptyList()
            }
          }

          MANIFEST_MEDIA_TYPE -> {
            if (withContext(Dispatchers.IO) { blob(desc).exists() }) {
              fetch(desc).use {
                val m: Manifest = Json.decodeFromStream(it)
                listOf(desc, m.config) + expand(m.layers)
              }
            } else {
              emptyList()
            }
          }

          else -> listOf(desc)
        }
      }
      .toSet()
  }

  /**
   * Removes a reference and its associated content from the layout.
   *
   * Resolves the reference to a descriptor and then removes that descriptor and all its referenced
   * content that isn't used by other artifacts.
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
  // TODO: ensure removals do not impact other images through unit tests
  @OptIn(ExperimentalSerializationApi::class)
  @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
  suspend fun remove(descriptor: Descriptor): Result<Boolean> = runCatching {
    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }

    refCount.incrementAndGet()

    try {
      mu.withLock {
        val file = blob(descriptor)

        val exists = withContext(Dispatchers.IO) { file.exists() }

        if (!exists) {
          return@runCatching true
        }

        return when (descriptor.mediaType) {
          INDEX_MEDIA_TYPE -> {
            val indexToRemove: Index = fetch(descriptor).use { Json.decodeFromStream(it) }

            index.manifests.removeAll { it.digest == descriptor.digest }

            withContext(Dispatchers.IO) { syncIndex() }

            for (manifestDesc in indexToRemove.manifests) {
              remove(manifestDesc).getOrThrow()
            }

            val deleted = withContext(Dispatchers.IO) { file.delete() }

            Result.success(deleted)
          }

          MANIFEST_MEDIA_TYPE -> {
            val manifests = index.manifests.filter { it.digest != descriptor.digest }

            index.manifests.removeAll { it.digest == descriptor.digest }

            withContext(Dispatchers.IO) { syncIndex() }

            val allOtherLayers = expand(manifests)

            if (allOtherLayers.contains(descriptor)) {
              throw OCIException.UnableToRemove(
                descriptor,
                "manifest is referenced by another artifact",
              )
            }

            val manifest: Manifest = fetch(descriptor).use { Json.decodeFromStream(it) }

            (manifest.layers + manifest.config).forEach { layer ->
              if (!allOtherLayers.contains(layer)) {
                remove(layer).getOrThrow()
              }
            }

            val deleted = withContext(Dispatchers.IO) { file.delete() }

            Result.success(deleted)
          }

          else -> {
            val ret = withContext(Dispatchers.IO) { file.delete() }
            Result.success(ret)
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
   * Synchronizes the in-memory index to disk.
   *
   * Writes the current state of the index to the index.json file in the layout.
   */
  internal fun syncIndex() {
    File("$root/$IMAGE_INDEX_FILE").writeText(Json.encodeToString(index))
  }

  /**
   * Gets the File object for a blob's location in the layout.
   *
   * @param descriptor The descriptor of the blob
   */
  private fun blob(descriptor: Descriptor): File {
    return File("$root/$IMAGE_BLOBS_DIR/${descriptor.digest.algorithm}/${descriptor.digest.hex}")
  }

  /**
   * Pushes content to the layout from a byte channel.
   *
   * Writes the content to disk, verifying its size and digest match the descriptor. Supports
   * resumable uploads by calculating the digest of existing content plus new content.
   *
   * @param descriptor The descriptor of the content being pushed
   * @param stream The byte channel containing the content
   * @return Flow emitting the number of bytes written in each chunk
   * @throws OCIException.SizeMismatch if the final size doesn't match the descriptor
   * @throws OCIException.DigestMismatch if the final digest doesn't match the descriptor
   */
  fun push(descriptor: Descriptor, stream: InputStream): Flow<Int> = channelFlow {
    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }

    refCount.incrementAndGet()

    try {
      mu.withLock {
        val ok = exists(descriptor).getOrDefault(false)

        if (!ok) {
          val file = blob(descriptor)
          val md = descriptor.digest.algorithm.hasher()
          // If resuming a download, start calculating the SHA from the data on disk
          //
          // it is up to the caller to properly resume the stream at the proper location,
          // otherwise a DigestMismatch will occur
          val fileExists = withContext(Dispatchers.IO) { file.exists() }
          if (fileExists) {
            withContext(Dispatchers.IO) {
              file.inputStream().use { fis ->
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                  md.update(buffer, 0, bytesRead)
                  // Not emitting bytes read from existing file
                }
              }
            }
          }

          val buffer = ByteArray(4 * 1024)
          var bytesRead: Int
          withContext(Dispatchers.IO) {
            FileOutputStream(file, true).use { out ->
              while (stream.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
                out.write(buffer, 0, bytesRead)

                send(bytesRead)
                yield()
              }
            }
          }

          val length = withContext(Dispatchers.IO) { file.length() }
          if (length != descriptor.size) {
            throw OCIException.SizeMismatch(descriptor, file.length())
          }

          val digest = Digest(descriptor.digest.algorithm, md.digest())

          if (digest != descriptor.digest) {
            withContext(Dispatchers.IO) { file.delete() }
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
   * Retrieves content from the layout as an input stream.
   *
   * @param descriptor The descriptor of the content to fetch
   */
  suspend fun fetch(descriptor: Descriptor): InputStream =
    withContext(Dispatchers.IO) { FileInputStream(blob(descriptor)) }

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
   * Finds the first descriptor in the index that matches the reference and optionally the platform
   * selector.
   *
   * @param reference The reference to resolve
   * @param platformResolver Optional function to select a specific platform
   */
  suspend fun resolve(
    reference: Reference,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Result<Descriptor> {
    return resolve { desc ->
      val refMatches = desc.annotations?.annotationRefName == reference.toString()
      val platformMatches =
        if (platformResolver != null && desc.platform != null) {
          platformResolver(desc.platform)
        } else {
          true
        }
      refMatches && platformMatches
    }
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
  // TODO: figure out how to use sealed Versioned here as well?
  //
  // NOTE: this edits an annotation on the descriptor, object equality checks will not work anymore
  // TODO: unit test tagging
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
    // untag the first manifests w/ this exact ref, there should only be one
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
    withContext(Dispatchers.IO) { syncIndex() }
  }

  /**
   * Lists all manifests in the layout.
   *
   * @return List of all manifest descriptors in the layout's index
   */
  fun catalog(): List<Descriptor> {
    return index.manifests.toList()
  }

  /**
   * Prunes all layers on disk that are not referenced by any manifest or index in the Layout's
   * index.
   *
   * This is a "stop the world" style function and MUST NOT run during any other operations. It
   * should be used to clean up zombie layers that might be left on disk if a remove operation is
   * interrupted.
   */
  suspend fun gc(): Result<List<Digest>> = runCatching {
    check(pushing.isEmpty()) { "there are downloads in progress" }

    val referencedDescriptors = expand(index.manifests).toSet()

    val blobsOnDisk = mutableListOf<Digest>()

    withContext(Dispatchers.IO) {
      val sha256Dir = File("$root/$IMAGE_BLOBS_DIR/sha256")
      if (sha256Dir.exists() && sha256Dir.isDirectory) {
        sha256Dir.listFiles()?.forEach { file ->
          if (file.isFile) {
            val digest = Digest(RegisteredAlgorithm.SHA256, file.name)
            blobsOnDisk.add(digest)
          }
        }
      }

      val sha512Dir = File("$root/$IMAGE_BLOBS_DIR/sha512")
      if (sha512Dir.exists() && sha512Dir.isDirectory) {
        sha512Dir.listFiles()?.forEach { file ->
          if (file.isFile) {
            val digest = Digest(RegisteredAlgorithm.SHA512, file.name)
            blobsOnDisk.add(digest)
          }
        }
      }
    }

    withContext(Dispatchers.IO) {
      blobsOnDisk
        .filter { it !in referencedDescriptors.map { desc -> desc.digest } }
        .mapNotNull { zombieDigest ->
          val file = File("$root/$IMAGE_BLOBS_DIR/${zombieDigest.algorithm}/${zombieDigest.hex}")
          if (file.delete()) zombieDigest else null
        }
    }
  }
}
