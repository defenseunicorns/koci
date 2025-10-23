/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.client

import com.defenseunicorns.koci.models.ANNOTATION_REF_NAME
import com.defenseunicorns.koci.models.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.Reference
import com.defenseunicorns.koci.models.annotationRefName
import com.defenseunicorns.koci.models.content.Descriptor
import com.defenseunicorns.koci.models.content.Digest
import com.defenseunicorns.koci.models.content.Index
import com.defenseunicorns.koci.models.content.LayoutMarker
import com.defenseunicorns.koci.models.content.Manifest
import com.defenseunicorns.koci.models.content.Platform
import com.defenseunicorns.koci.models.content.RegisteredAlgorithm
import com.defenseunicorns.koci.models.errors.OCIError
import com.defenseunicorns.koci.models.errors.OCIException
import com.defenseunicorns.koci.models.errors.OCIResult
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

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
 * @property blobsPath The directory path where blobs are stored
 * @property stagingPath The directory path for staging/temporary operations
 */
@Suppress("detekt:TooManyFunctions")
class Layout
private constructor(
  private val index: Index,
  private val root: String,
  private val blobsPath: String,
  private val stagingPath: String,
  private val strictChecking: Boolean,
) {
  internal val pushing = ConcurrentHashMap<Descriptor, Pair<Mutex, AtomicInteger>>()
  private val fileSystem = FileSystem.SYSTEM

  /**
   * Checks if a blob exists in the layout and verifies its integrity.
   *
   * Performs size and digest verification to ensure the content matches the descriptor's metadata.
   *
   * @param descriptor The descriptor of the blob to check
   * @return Ok(true) if blob exists and is valid, Ok(false) if not found, or Err with the specific
   *   error
   */
  fun exists(descriptor: Descriptor): OCIResult<Boolean> {
    val path = blob(descriptor)

    if (!fileSystem.exists(path)) {
      return OCIResult.ok(false)
    }

    val length = fileSystem.metadata(path).size ?: 0L
    if (length != descriptor.size) {
      return OCIResult.err(OCIError.SizeMismatch(descriptor, length))
    }

    val digest =
      try {
        fileSystem.source(path).buffer().use { source ->
          val buffer = ByteArray(1024)
          val md = descriptor.digest.algorithm.hasher()
          var bytesRead: Int
          while (source.read(buffer).also { bytesRead = it } != -1) {
            md.update(buffer, 0, bytesRead)
          }
          Digest(descriptor.digest.algorithm, md.digest())
        }
      } catch (e: Exception) {
        return OCIResult.err(OCIError.IOError("Failed to read blob: ${e.message}", e))
      }

    if (digest != descriptor.digest) {
      return OCIResult.err(OCIError.DigestMismatch(descriptor, digest))
    }

    return OCIResult.ok(true)
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
  private fun expand(descriptors: List<Descriptor>): Set<Descriptor> {
    return descriptors
      .flatMap { desc ->
        when (desc.mediaType) {
          INDEX_MEDIA_TYPE -> {
            if (fileSystem.exists(blob(desc))) {
              val i: Index = fetch(desc).use { Json.decodeFromStream(it) }
              listOf(desc) + i.manifests + expand(i.manifests)
            } else {
              emptyList()
            }
          }

          MANIFEST_MEDIA_TYPE -> {
            if (fileSystem.exists(blob(desc))) {
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
  suspend fun remove(reference: Reference): OCIResult<Boolean> =
    resolve(reference).flatMap { descriptor -> remove(descriptor) }

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
  suspend fun remove(descriptor: Descriptor): OCIResult<Boolean> {
    val (mu, refCount) = pushing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }

    refCount.incrementAndGet()

    return try {
      mu.withLock {
        val path = blob(descriptor)

        if (!fileSystem.exists(path)) {
          return OCIResult.ok(false) // TODO: File was not removed since it does not exist
        }

        return when (descriptor.mediaType) {
          INDEX_MEDIA_TYPE -> {
            val indexToRemove: Index = fetch(descriptor).use { Json.decodeFromStream(it) }

            index.manifests.removeAll { it.digest == descriptor.digest }

            syncIndex()

            for (manifestDesc in indexToRemove.manifests) {
              remove(manifestDesc)
            }

            fileSystem.delete(path)

            OCIResult.ok(true)
          }

          MANIFEST_MEDIA_TYPE -> {
            val manifests = index.manifests.filter { it.digest != descriptor.digest }

            index.manifests.removeAll { it.digest == descriptor.digest }

            syncIndex()

            val allOtherLayers = expand(manifests)

            if (allOtherLayers.contains(descriptor)) {
              return OCIResult.err(
                OCIError.UnableToRemove(descriptor, "manifest is referenced by another artifact")
              )
            }

            val manifest: Manifest = fetch(descriptor).use { Json.decodeFromStream(it) }

            (manifest.layers + manifest.config).forEach { layer ->
              if (!allOtherLayers.contains(layer)) {
                remove(layer)
              }
            }

            fileSystem.delete(path)

            OCIResult.ok(true)
          }

          else -> {
            fileSystem.delete(path)
            OCIResult.ok(true)
          }
        }
      }
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to remove descriptor: ${e.message}", e))
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
    fileSystem.write("$root/$IMAGE_INDEX_FILE".toPath()) { writeUtf8(Json.encodeToString(index)) }
  }

  /**
   * Returns the path to a blob file based on its descriptor.
   *
   * @param descriptor The descriptor of the blob
   */
  private fun blob(descriptor: Descriptor): Path {
    return "$blobsPath/${descriptor.digest.algorithm}/${descriptor.digest.hex}".toPath()
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
        val ok = exists(descriptor).getOrNull() ?: false

        if (!ok) {
          val path = blob(descriptor)
          val md = descriptor.digest.algorithm.hasher()
          // If resuming a download, start calculating the SHA from the data on disk
          //
          // it is up to the caller to properly resume the stream at the proper location,
          // otherwise a DigestMismatch will occur
          if (fileSystem.exists(path)) {
            fileSystem.source(path).buffer().use { source ->
              val buffer = ByteArray(32 * 1024)
              var bytesRead: Int
              while (source.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
                // Not emitting bytes read from existing file
              }
            }
          }

          val buffer = ByteArray(4 * 1024)
          var bytesRead: Int
          fileSystem.appendingSink(path).buffer().use { sink ->
            while (stream.read(buffer).also { bytesRead = it } != -1) {
              md.update(buffer, 0, bytesRead)
              sink.write(buffer, 0, bytesRead)

              send(bytesRead)
              yield()
            }
          }

          val length = fileSystem.metadata(path).size ?: 0L
          if (length != descriptor.size) {
            fileSystem.delete(path)
            close(IllegalStateException("Size mismatch: expected ${descriptor.size}, got $length"))
            return@channelFlow
          }

          val digest = Digest(descriptor.digest.algorithm, md.digest())

          if (digest != descriptor.digest) {
            fileSystem.delete(path)
            close(
              IllegalStateException("Digest mismatch: expected ${descriptor.digest}, got $digest")
            )
            return@channelFlow
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
  fun fetch(descriptor: Descriptor): InputStream =
    fileSystem.source(blob(descriptor)).buffer().inputStream()

  /**
   * Resolves a descriptor from the layout's index using a predicate function.
   *
   * @param predicate Function that returns true for the desired descriptor
   */
  suspend fun resolve(predicate: suspend (Descriptor) -> Boolean): OCIResult<Descriptor> {
    val descriptor = index.manifests.firstOrNull { predicate(it) }
    return if (descriptor != null) {
      OCIResult.ok(descriptor)
    } else {
      OCIResult.err(OCIError.DescriptorNotFound("No descriptor matched the predicate"))
    }
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
  ): OCIResult<Descriptor> {
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
      .mapErr { OCIError.DescriptorNotFound("Reference not found: $reference") }
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
  fun tag(descriptor: Descriptor, reference: Reference): OCIResult<Unit> {
    if (descriptor.mediaType.isEmpty()) {
      return OCIResult.err(OCIError.Generic("Descriptor mediaType cannot be empty"))
    }
    if (descriptor.mediaType != MANIFEST_MEDIA_TYPE && descriptor.mediaType != INDEX_MEDIA_TYPE) {
      return OCIResult.err(
        OCIError.UnsupportedManifest(
          descriptor.mediaType,
          "Only manifests and indexes can be tagged",
        )
      )
    }
    if (descriptor.size <= 0) {
      return OCIResult.err(OCIError.Generic("Descriptor size must be greater than 0"))
    }

    return try {
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
      syncIndex()
      OCIResult.ok(Unit)
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to tag descriptor: ${e.message}", e))
    }
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
  fun gc(): OCIResult<List<Digest>> {
    if (pushing.isNotEmpty()) {
      return OCIResult.err(OCIError.Generic("Cannot run GC: downloads are in progress"))
    }

    return try {

      val referencedDescriptors = expand(index.manifests).toSet()

      val blobsOnDisk = mutableListOf<Digest>()

      listOf("sha256", "sha512").forEach { sha ->
        val dir = "$blobsPath/$sha".toPath()
        if (fileSystem.exists(dir) && fileSystem.metadata(dir).isDirectory) {
          fileSystem.list(dir).forEach { path ->
            if (!fileSystem.metadata(path).isDirectory) {
              val digest = Digest(RegisteredAlgorithm.SHA256, path.name)
              blobsOnDisk.add(digest)
            }
          }
        }
      }

      val removed =
        blobsOnDisk
          .filter { it !in referencedDescriptors.map { desc -> desc.digest } }
          .mapNotNull { zombieDigest ->
            val path = "$blobsPath/${zombieDigest.algorithm}/${zombieDigest.hex}".toPath()
            runCatching { fileSystem.delete(path) }.map { zombieDigest }.getOrNull()
          }
      OCIResult.ok(removed)
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Failed to run GC: ${e.message}", e))
    }
  }

  companion object {
    /** LAYOUT_VERSION is the version of the OCI Image Layout */
    const val LAYOUT_VERSION = "1.0.0"

    /** IMAGE_LAYOUT_FILE is the file name containing [LayoutMarker] in an OCI Image Layout */
    const val IMAGE_LAYOUT_FILE = "oci-layout"

    /**
     * IMAGE_INDEX_FILE is the file name of the entry point for references and descriptors in an OCI
     * Image Layout
     */
    const val IMAGE_INDEX_FILE = "index.json"

    /**
     * Builds the Layout with the configured options.
     *
     * @return OCIResult containing the Layout or an error
     */
    fun create(
      rootPath: String,
      blobsPath: String,
      stagingPath: String,
      strictChecking: Boolean,
    ): OCIResult<Layout> {
      val fs = FileSystem.SYSTEM
      val rootDir = rootPath.toPath()
      val indexLocation = "$rootPath/$IMAGE_INDEX_FILE".toPath()
      val layoutFileLocation = "$rootPath/$IMAGE_LAYOUT_FILE".toPath()

      // Handle root directory creation
      if (!fs.exists(rootDir)) {
        try {
          fs.createDirectories(rootDir)
        } catch (e: IOException) {
          return OCIResult.err(OCIError.IOError("Failed to create root directory: ${e.message}", e))
        }
      } else {
        if (!fs.metadata(rootDir).isDirectory) {
          return OCIResult.err(
            OCIError.InvalidLayout(rootPath, "Path exists but is not a directory")
          )
        }
      }

      // Handle oci-layout file
      if (!fs.exists(layoutFileLocation)) {
        try {
          fs.write(layoutFileLocation) {
            writeUtf8(Json.encodeToString(LayoutMarker(LAYOUT_VERSION)))
          }
        } catch (e: Exception) {
          return OCIResult.err(OCIError.IOError("Failed to write layout file: ${e.message}", e))
        }
      }

      // Determine which index to use
      val index =
        try {
          when {
            fs.exists(indexLocation) -> fs.read(indexLocation) { Json.decodeFromString(readUtf8()) }
            else -> Index()
          }
        } catch (e: Exception) {
          return OCIResult.err(OCIError.IOError("Failed to read index: ${e.message}", e))
        }

      // Create blob storage directories
      try {
        listOf("sha256", "sha512").forEach { algorithm ->
          fs.createDirectories("$blobsPath/$algorithm".toPath())
        }
      } catch (e: Exception) {
        return OCIResult.err(OCIError.IOError("Failed to create blob directories: ${e.message}", e))
      }

      // Create directory for staging operations
      try {
        fs.createDirectories(stagingPath.toPath())
      } catch (e: Exception) {
        return OCIResult.err(OCIError.IOError("Failed to create temp directory: ${e.message}", e))
      }

      return OCIResult.ok(
        Layout(
          index = index,
          root = rootPath,
          blobsPath = blobsPath,
          stagingPath = stagingPath,
          strictChecking = strictChecking,
        )
      )
    }
  }
}
