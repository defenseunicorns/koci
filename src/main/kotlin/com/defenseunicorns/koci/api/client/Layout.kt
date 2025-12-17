/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.client

import com.defenseunicorns.koci.KociLogger
import com.defenseunicorns.koci.TransferCoordinator
import com.defenseunicorns.koci.api.KociLogLevel
import com.defenseunicorns.koci.api.OCIException
import com.defenseunicorns.koci.api.models.Descriptor
import com.defenseunicorns.koci.api.models.Digest
import com.defenseunicorns.koci.api.models.Index
import com.defenseunicorns.koci.api.models.LayoutMarker
import com.defenseunicorns.koci.api.models.Manifest
import com.defenseunicorns.koci.api.models.Platform
import com.defenseunicorns.koci.api.models.Reference
import com.defenseunicorns.koci.api.models.RegisteredAlgorithm
import com.defenseunicorns.koci.models.ANNOTATION_REF_NAME
import com.defenseunicorns.koci.models.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.annotationRefName
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okio.FileSystem
import okio.HashingSource.Companion.sha256
import okio.HashingSource.Companion.sha512
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.source

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
 * @property rootPath The root directory path where the layout is stored
 * @property blobsPath The directory path where blobs are stored
 * @property stagingPath The directory path for staging/temporary operations
 * @property transferCoordinator The transfer coordinator for managing concurrent transfers
 */
class Layout
private constructor(
  internal val index: Index,
  private val rootPath: String,
  private val blobsPath: String,
  private val stagingPath: String,
  private val strictChecking: Boolean,
  private val logger: KociLogger,
  private val transferCoordinator: TransferCoordinator,
) {
  private val fileSystem = FileSystem.SYSTEM
  private val removing = ConcurrentHashMap<Descriptor, Pair<Mutex, AtomicInteger>>()

  /**
   * Checks if a blob exists in the layout and verifies its integrity.
   *
   * Performs size verification and optionally digest verification based on strictChecking setting.
   * When strictChecking is enabled, reads and hashes the entire blob to verify integrity. When
   * disabled, only checks file existence and size for better performance.
   *
   * @param descriptor The descriptor of the blob to check
   * @return Ok(true) if blob exists and is valid, Ok(false) if not found, or Err with the specific
   *   error
   */
  fun exists(descriptor: Descriptor): Boolean {
    val path = blob(descriptor)

    if (!fileSystem.exists(path)) {
      return false
    }

    val size = size(descriptor)
    if (size != descriptor.size) {
      logger.e("Size mismatch for ${descriptor.digest}: expected ${descriptor.size}, got $size")
      return false
    }

    // Skip expensive digest verification when strictChecking is disabled
    if (!strictChecking) {
      return true
    }

    val digest =
      try {
        fileSystem.source(path).use { source ->
          val hashingSource =
            when (descriptor.digest.algorithm) {
              RegisteredAlgorithm.SHA256 -> sha256(source)
              RegisteredAlgorithm.SHA512 -> sha512(source)
            }
          hashingSource.buffer().readAll(blackholeSink())
          Digest.create(descriptor.digest.algorithm, hashingSource.hash.toByteArray())
        }
      } catch (e: Exception) {
        logger.e("Failed to read blob", e)
        return false
      }

    if (digest != descriptor.digest) {
      logger.e("Digest mismatch for ${descriptor.digest}: computed $digest")
      return false
    }

    logger.d("Blob exists and verified: ${descriptor.digest}")
    return true
  }

  fun size(descriptor: Descriptor): Long {
    logger.d("Checking size: $descriptor")

    val path = blob(descriptor)

    if (!fileSystem.exists(path)) {
      logger.d("Blob does not exist: ${descriptor.digest}")
      return 0L
    }

    return fileSystem.metadata(path).size ?: 0L
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
  suspend fun remove(reference: Reference): Boolean {
    logger.d("Removing reference: $reference")
    val resolvedReference = resolve(reference)

    return resolvedReference?.let { remove(it) } ?: false
  }

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
  suspend fun remove(descriptor: Descriptor): Boolean {
    logger.d("Removing descriptor: ${descriptor.digest}")

    val path = blob(descriptor)

    if (!fileSystem.exists(path)) {
      logger.d("Descriptor not found, nothing to remove: ${descriptor.digest}")
      return false
    }

    val (mu, refCount) = removing.computeIfAbsent(descriptor) { Pair(Mutex(), AtomicInteger(0)) }

    refCount.incrementAndGet()

    try {
      mu.withLock {
        when (descriptor.mediaType) {
          INDEX_MEDIA_TYPE -> {
            val indexToRemove: Index = fetch(descriptor).use { Json.decodeFromStream(it) }

            index.manifests.removeAll { it.digest == descriptor.digest }

            syncIndex()

            for (manifestDesc in indexToRemove.manifests) {
              remove(manifestDesc)
            }

            fileSystem.delete(path)
            logger.d("Removed index: ${descriptor.digest}")

            return true
          }

          MANIFEST_MEDIA_TYPE -> {
            val manifests = index.manifests.filter { it.digest != descriptor.digest }

            index.manifests.removeAll { it.digest == descriptor.digest }

            syncIndex()

            val allOtherLayers = expand(manifests)

            if (allOtherLayers.contains(descriptor)) {
              logger.d("manifest is referenced by another artifact")
              return false
            }

            val manifest: Manifest = fetch(descriptor).use { Json.decodeFromStream(it) }

            (manifest.layers + manifest.config).forEach { layer ->
              if (!allOtherLayers.contains(layer)) {
                remove(layer)
              }
            }

            fileSystem.delete(path)
            logger.d("Removed manifest: ${descriptor.digest}")

            return true
          }

          else -> {
            fileSystem.delete(path)
            logger.d("Removed blob: ${descriptor.digest}")
            return true
          }
        }
      }
    } catch (e: Exception) {
      logger.d("Failed to remove descriptor: ${descriptor.digest}")
      return false
    } finally {
      val pair = removing[descriptor]
      if (pair != null) {
        val count = pair.second.decrementAndGet()
        if (count <= 0) {
          removing.remove(descriptor, pair)
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
    fileSystem.write("$rootPath/$IMAGE_INDEX_FILE".toPath()) {
      writeUtf8(Json.encodeToString(index))
    }
  }

  /**
   * Returns the path to a blob file in the verified blobs directory.
   *
   * @param descriptor The descriptor of the blob
   */
  private fun blob(descriptor: Descriptor): Path {
    return "$blobsPath/${descriptor.digest.algorithm}/${descriptor.digest.hex}".toPath()
  }

  /**
   * Returns the path to a staging file for incomplete/unverified content.
   *
   * Staging files are written during transfers and only moved to the blobs directory after full
   * verification (size and digest). This ensures the blobs directory only contains verified
   * content.
   *
   * @param descriptor The descriptor of the content being staged
   */
  private fun staging(descriptor: Descriptor): Path {
    return "$stagingPath/${descriptor.digest.algorithm}/${descriptor.digest.hex}".toPath()
  }

  /**
   * Pushes content to the layout from an input stream.
   *
   * Writes the content to disk, verifying its size and digest match the descriptor. Supports
   * resumable uploads by calculating the digest of existing content plus new content.
   *
   * Uses the transfer coordinator to prevent duplicate concurrent writes to the same descriptor.
   *
   * @param descriptor The descriptor of the content being pushed
   * @param stream The input stream containing the content
   * @return Flow emitting OCIResult with the number of bytes written in each chunk, or an error
   */
  fun push(descriptor: Descriptor, stream: InputStream): Flow<Int?> =
    transferCoordinator.transfer(descriptor = descriptor) { actualPush(descriptor, stream) }

  private fun hashExistingStagedData(stagingPath: Path, md: java.security.MessageDigest): Long {
    var totalBytesRead = 0L
    fileSystem.source(stagingPath).buffer().use { source ->
      val buffer = ByteArray(BUFFER_SIZE)
      var bytesRead: Int
      while (source.read(buffer).also { bytesRead = it } != -1) {
        md.update(buffer, 0, bytesRead)
        totalBytesRead += bytesRead
      }
    }
    return totalBytesRead
  }

  private fun verifySizeAndDigest(
    stagingPath: Path,
    descriptor: Descriptor,
    md: java.security.MessageDigest,
  ): Boolean {
    val length = fileSystem.metadata(stagingPath).size ?: 0L
    if (length != descriptor.size) {
      logger.e(
        "Size mismatch pushing ${descriptor.digest}: expected ${descriptor.size}, got $length"
      )
      fileSystem.delete(stagingPath)
      return false
    }

    val digest = Digest.create(descriptor.digest.algorithm, md.digest())
    if (digest != descriptor.digest) {
      logger.e("Digest mismatch pushing ${descriptor.digest}: computed $digest")
      fileSystem.delete(stagingPath)
      return false
    }
    return true
  }

  private fun moveStagingToFinal(
    stagingPath: Path,
    finalPath: Path,
    descriptor: Descriptor,
  ): Boolean {
    fileSystem.createDirectories(finalPath.parent!!)
    logger.d("Finalization move: $stagingPath -> $finalPath")

    try {
      fileSystem.atomicMove(stagingPath, finalPath)
    } catch (_: IOException) {
      logger.d("Atomic move failed, using copy: ${descriptor.digest}")
      fileSystem.copy(stagingPath, finalPath)
      fileSystem.delete(stagingPath)
      return false
    }

    if (!exists(descriptor)) {
      logger.e("Final verification failed: ${descriptor.digest} not found after move")
      return false
    }
    return true
  }

  /**
   * Performs the actual push operation to disk.
   *
   * This is called by the coordinator when this is the first request for a descriptor. Other
   * concurrent requests for the same descriptor will wait for this to complete.
   */
  private fun actualPush(descriptor: Descriptor, stream: InputStream): Flow<Int?> = flow {
    logger.d("Pushing to disk: ${descriptor.digest}")

    if (exists(descriptor)) return@flow

    val stagingPath = staging(descriptor)
    val finalPath = blob(descriptor)
    val md = descriptor.digest.algorithm.hasher()

    fileSystem.createDirectories(stagingPath.parent!!)

    val resumeOffset =
      if (fileSystem.exists(stagingPath)) fileSystem.metadata(stagingPath).size ?: 0L else 0L

    var totalBytesRead =
      if (resumeOffset > 0) {
        logger.d("Resuming push for ${descriptor.digest}, offset=$resumeOffset")
        hashExistingStagedData(stagingPath, md)
      } else {
        0L
      }

    // Append new data, starting after the resume offset
    fileSystem.appendingSink(stagingPath).buffer().use { sink ->
      stream.source().buffer().use { source ->
        if (resumeOffset > 0) {
          try {
            source.skip(resumeOffset)
          } catch (e: Exception) {
            logger.e("Failed to skip with offset of $resumeOffset", e)
            return@flow emit(null)
          }
        }

        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (source.read(buffer).also { bytesRead = it } != -1) {
          md.update(buffer, 0, bytesRead)
          sink.write(buffer, 0, bytesRead)
          totalBytesRead += bytesRead

          val fraction = (totalBytesRead.toDouble() / descriptor.size.toDouble()).coerceIn(0.0, 1.0)
          emit((fraction * PERCENTAGE_MULTIPLIER).toInt())
        }
      }
    }

    if (!verifySizeAndDigest(stagingPath, descriptor, md)) {
      return@flow emit(null)
    }

    if (!moveStagingToFinal(stagingPath, finalPath, descriptor)) {
      return@flow emit(null)
    }

    logger.d("Successfully downloaded ${descriptor.digest}")
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
  fun resolve(predicate: (Descriptor) -> Boolean): Descriptor? {
    val descriptor = index.manifests.firstOrNull { predicate(it) }

    if (descriptor == null) {
      logger.d("No descriptor matched the predicate")
    }

    return descriptor
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
  fun resolve(
    reference: Reference,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Descriptor? {
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
  // TODO: unit test tagging
  fun tag(descriptor: Descriptor, reference: Reference): Boolean {
    if (descriptor.mediaType.isEmpty()) {
      logger.e("Descriptor mediaType cannot be empty")
      return false
    }
    if (descriptor.mediaType != MANIFEST_MEDIA_TYPE && descriptor.mediaType != INDEX_MEDIA_TYPE) {
      logger.e("Only manifests and indexes can be tagged")
      return false
    }
    if (descriptor.size <= 0) {
      logger.e("Descriptor size must be greater than 0")
      return false
    }

    try {
      val isReferenceValid = reference.validate()

      if (!isReferenceValid) {
        return false
      }

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
      return true
    } catch (e: Exception) {
      logger.e("Failed to tag descriptor: ${e.message}", e)
      return false
    }
  }

  /**
   * Lists all manifests in the layout.
   *
   * @return List of all manifest descriptors in the layout's index
   */
  fun catalog(): List<Descriptor> = index.manifests.toList()

  /**
   * Cleans up incomplete files in the staging directory.
   *
   * Removes all files from staging that were left behind from interrupted transfers. This is safe
   * to call at any time as staging files are temporary and will be re-downloaded if needed.
   *
   * @return Ok with number of files cleaned up, or Err if cleanup fails
   */
  private fun cleanStaging(): Boolean {
    return try {
      var cleaned = 0

      listOf("sha256", "sha512").forEach { algorithm ->
        cleaned += cleanStagingForAlgorithm(algorithm)
      }
      logger.d("Cleaned $cleaned staging files")
      true
    } catch (e: Exception) {
      logger.e("Failed to clean staging directory", e)
      false
    }
  }

  private fun cleanStagingForAlgorithm(algorithm: String): Int {
    var cleaned = 0
    val dir = "$stagingPath/$algorithm".toPath()

    if (!fileSystem.exists(dir) || !fileSystem.metadata(dir).isDirectory) {
      return 0
    }

    fileSystem.list(dir).forEach { path ->
      if (!fileSystem.metadata(path).isDirectory) {
        runCatching {
          fileSystem.delete(path)
          cleaned++
          logger.d("Cleaned staging file: ${path.name}")
        }
      }
    }
    return cleaned
  }

  /**
   * Performs garbage collection on unreferenced blobs.
   *
   * Removes blobs from disk that are not referenced by any manifest in the index. This helps
   * reclaim disk space from orphaned content. Returns list of digests that were removed.
   *
   * Also cleans up any incomplete files in the staging directory.
   *
   * @return Ok with list of removed digests, or Err if GC fails or transfers are in progress
   * @throws OCIException.Generic if downloads are in progress - GC should not run while
   *   interrupted.
   */
  fun gc(): List<Digest> {
    if (transferCoordinator.activeTransfers() > 0 || removing.isNotEmpty()) {
      logger.w("Cannot run GC: downloads are in progress or removal is in progress")
      return emptyList()
    }

    // Clean up staging first
    cleanStaging()

    return try {
      val referencedDescriptors = expand(index.manifests).toSet()
      val blobsOnDisk = collectBlobsOnDisk()
      removeUnreferencedBlobs(blobsOnDisk, referencedDescriptors)
    } catch (e: Exception) {
      logger.e("Failed to run GC", e)
      emptyList()
    }
  }

  private fun collectBlobsOnDisk(): List<Digest> {
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
    return blobsOnDisk
  }

  private fun removeUnreferencedBlobs(
    blobsOnDisk: List<Digest>,
    referencedDescriptors: Set<Descriptor>,
  ): List<Digest> {
    return blobsOnDisk
      .filter { it !in referencedDescriptors.map { desc -> desc.digest } }
      .mapNotNull { zombieDigest ->
        val path = "$blobsPath/${zombieDigest.algorithm}/${zombieDigest.hex}".toPath()
        runCatching { fileSystem.delete(path) }.map { zombieDigest }.getOrNull()
      }
  }

  companion object {
    private const val BUFFER_SIZE = 32 * 1024
    private const val PERCENTAGE_MULTIPLIER = 100.0
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
      blobsPath: String = "$rootPath/blobs",
      stagingPath: String = "$rootPath/staging",
      strictChecking: Boolean = true,
      logLevel: KociLogLevel = KociLogLevel.WARN,
    ): Layout? {
      val logger = KociLogger(logLevel)

      val fs = FileSystem.SYSTEM
      val rootDir = rootPath.toPath()
      val indexLocation = "$rootPath/$IMAGE_INDEX_FILE".toPath()
      val layoutFileLocation = "$rootPath/$IMAGE_LAYOUT_FILE".toPath()

      // Handle root directory creation
      if (!fs.exists(rootDir)) {
        try {
          logger.d("Creating root directory at $rootPath")
          fs.createDirectories(rootDir)
        } catch (e: IOException) {
          logger.e("Failed to create root directory", e)
          return null
        }
      } else {
        if (!fs.metadata(rootDir).isDirectory) {
          logger.e("Path exists but is not a directory: $rootPath")
          return null
        }
      }

      // Handle oci-layout file
      if (!fs.exists(layoutFileLocation)) {
        try {
          logger.d("Layout version: $LAYOUT_VERSION")
          fs.write(layoutFileLocation) {
            writeUtf8(Json.encodeToString(LayoutMarker(LAYOUT_VERSION)))
          }
        } catch (e: Exception) {
          logger.e("Failed to write layout file", e)
          return null
        }
      }

      // Determine which index to use
      val index =
        try {
          when {
            fs.exists(indexLocation) -> {
              logger.d("Loading index from $indexLocation")
              fs.read(indexLocation) { Json.decodeFromString(readUtf8()) }
            }
            else -> {
              logger.d("Index not found at $indexLocation, creating new index")
              fs.write(indexLocation) { writeUtf8("{}") }
              Index()
            }
          }
        } catch (e: Exception) {
          logger.e("Failed to read index: ${e.message}", e)
          return null
        }

      // Create blob storage directories
      try {
        listOf("sha256", "sha512").forEach { algorithm ->
          logger.d("Creating blob directory at $blobsPath/$algorithm")
          fs.createDirectories("$blobsPath/$algorithm".toPath())
        }
      } catch (e: Exception) {
        logger.e("Failed to create blob directories", e)
        return null
      }

      // Create directory for staging operations
      try {
        logger.d("Creating staging directory at $stagingPath")
        fs.createDirectories(stagingPath.toPath())
      } catch (e: Exception) {
        logger.e("Failed to create temp directory", e)
        return null
      }

      logger.d("Layout created")

      return Layout(
        index = index,
        rootPath = rootPath,
        blobsPath = blobsPath,
        stagingPath = stagingPath,
        strictChecking = strictChecking,
        logger = logger,
        transferCoordinator = TransferCoordinator(logger),
      )
    }
  }
}
