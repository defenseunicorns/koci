/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.client.Layout
import com.defenseunicorns.koci.models.Descriptor
import com.defenseunicorns.koci.models.content.Digest
import com.defenseunicorns.koci.models.IMAGE_BLOBS_DIR
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.Manifest
import com.defenseunicorns.koci.models.errors.OCIResult
import com.defenseunicorns.koci.models.Reference
import com.defenseunicorns.koci.models.content.RegisteredAlgorithm
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LayoutTest {

  @TempDir lateinit var tempDir: Path

  private lateinit var layout: Layout
  private lateinit var rootDir: String

  @BeforeEach
  fun setup() = runBlocking {
    rootDir = tempDir.toString()
    layout =
      when (val result = Layout.create(rootDir)) {
        is OCIResult.Ok -> result.value
        is OCIResult.Err -> throw AssertionError("Failed to create layout: ${result.error}")
      }
  }

  @AfterEach
  fun cleanup() {
    FileSystem.SYSTEM.deleteRecursively(tempDir.toString().toPath())
  }

  @Test
  fun `test concurrent pushes of the same descriptor`() =
    runTest(timeout = kotlin.time.Duration.parse("PT15S")) {
      val content1 = "Hello World!\n".repeat(6000)
      val content2 = "Hello World!\n".repeat(6000)
      val blob1 =
        Descriptor(
          mediaType = TEST_BLOB_MEDIATYPE,
          digest =
            Digest(
              RegisteredAlgorithm.SHA256,
              RegisteredAlgorithm.SHA256.hasher().apply { update(content1.toByteArray()) }.digest(),
            ),
          size = content1.toByteArray().size.toLong(),
        )
      val blob2 =
        Descriptor(
          mediaType = TEST_BLOB_MEDIATYPE,
          digest =
            Digest(
              RegisteredAlgorithm.SHA256,
              RegisteredAlgorithm.SHA256.hasher().apply { update(content2.toByteArray()) }.digest(),
            ),
          size = content2.toByteArray().size.toLong(),
        )
      assertEquals(blob1, blob2)

      val r1 = async { layout.push(blob1, content1.byteInputStream()).collect() }
      val r2 = async { layout.push(blob2, content2.byteInputStream()).collect() }
      val r3 = async { layout.push(blob2, content2.byteInputStream()).collect() }
      awaitAll(r1, r2, r3)

      assertTrue(layout.exists(blob1).getOrNull() == true)
      assertTrue(layout.exists(blob2).getOrNull() == true)
    }

  @Test
  fun `test gc removes zombie layers`() = runTest {
    val configDescriptor =
      createTestBlob("config-content", "application/vnd.oci.image.config.v1+json")
    val layer1Descriptor =
      createTestBlob("layer1-content", "application/vnd.oci.image.layer.v1.tar+gzip")
    val layer2Descriptor =
      createTestBlob("layer2-content", "application/vnd.oci.image.layer.v1.tar+gzip")

    // Create a manifest that references config and layer1
    val manifest =
      Manifest(
        schemaVersion = 2,
        mediaType = MANIFEST_MEDIA_TYPE,
        config = configDescriptor,
        layers = listOf(layer1Descriptor),
        annotations = null,
      )

    val manifestJson = Json.encodeToString(manifest)
    val manifestStream = ByteArrayInputStream(manifestJson.toByteArray())
    val manifestDescriptor =
      Descriptor(
        mediaType = MANIFEST_MEDIA_TYPE,
        digest =
          Digest(
            RegisteredAlgorithm.SHA256,
            RegisteredAlgorithm.SHA256.hasher()
              .apply { update(manifestJson.toByteArray()) }
              .digest(),
          ),
        size = manifestJson.length.toLong(),
      )

    layout.push(manifestDescriptor, manifestStream).collect {}

    val reference = Reference(registry = "localhost", repository = "test", reference = "latest")
    when (val result = layout.tag(manifestDescriptor, reference)) {
      is OCIResult.Err -> throw AssertionError("Failed to tag: ${result.error}")
      is OCIResult.Ok -> {}
    }

    // Verify all blobs exist
    assertTrue(layout.exists(configDescriptor).getOrNull() == true)
    assertTrue(layout.exists(layer1Descriptor).getOrNull() == true)
    assertTrue(layout.exists(layer2Descriptor).getOrNull() == true)
    assertTrue(layout.exists(manifestDescriptor).getOrNull() == true)

    // Verify layer2 is a zombie (not referenced by any manifest)
    val zombiePath =
      "$rootDir/$IMAGE_BLOBS_DIR/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}"
        .toPath()
    assertTrue(FileSystem.SYSTEM.exists(zombiePath))

    val removedDigests =
      when (val result = layout.gc()) {
        is OCIResult.Ok -> result.value
        is OCIResult.Err -> throw AssertionError("GC failed: ${result.error}")
      }

    // Verify layer2 was removed
    assertFalse(FileSystem.SYSTEM.exists(zombiePath))

    // Verify only layer2 was removed
    assertEquals(1, removedDigests.size)
    assertEquals(layer2Descriptor.digest, removedDigests[0])

    // Verify other blobs still exist
    assertTrue(layout.exists(configDescriptor).getOrNull() == true)
    assertTrue(layout.exists(layer1Descriptor).getOrNull() == true)
    assertTrue(layout.exists(manifestDescriptor).getOrNull() == true)
  }

  @Suppress("detekt:LongMethod")
  @Test
  fun `test gc with interrupted remove operation`() = runTest {
    val configDescriptor =
      createTestBlob("config-content", "application/vnd.oci.image.config.v1+json")
    val layer1Descriptor =
      createTestBlob("layer1-content", "application/vnd.oci.image.layer.v1.tar+gzip")
    val layer2Descriptor =
      createTestBlob("layer2-content", "application/vnd.oci.image.layer.v1.tar+gzip")

    // Create a manifest that references config, layer1 and layer2
    val manifest =
      Manifest(
        schemaVersion = 2,
        mediaType = MANIFEST_MEDIA_TYPE,
        config = configDescriptor,
        layers = listOf(layer1Descriptor, layer2Descriptor),
        annotations = null,
      )

    val manifestJson = Json.encodeToString(manifest)
    val manifestStream = ByteArrayInputStream(manifestJson.toByteArray())
    val manifestDescriptor =
      Descriptor(
        mediaType = MANIFEST_MEDIA_TYPE,
        digest =
          Digest(
            RegisteredAlgorithm.SHA256,
            RegisteredAlgorithm.SHA256.hasher()
              .apply { update(manifestJson.toByteArray()) }
              .digest(),
          ),
        size = manifestJson.length.toLong(),
      )

    layout.push(manifestDescriptor, manifestStream).collect {}

    val reference = Reference(registry = "localhost", repository = "test", reference = "latest")
    when (val result = layout.tag(manifestDescriptor, reference)) {
      is OCIResult.Err -> throw AssertionError("Failed to tag: ${result.error}")
      is OCIResult.Ok -> {}
    }

    // Verify all blobs exist
    assertTrue(layout.exists(configDescriptor).getOrNull() == true)
    assertTrue(layout.exists(layer1Descriptor).getOrNull() == true)
    assertTrue(layout.exists(layer2Descriptor).getOrNull() == true)
    assertTrue(layout.exists(manifestDescriptor).getOrNull() == true)

    // Simulate an interrupted remove operation by manually removing the manifest from the index
    // but leaving the blobs on disk
    layout.index.manifests.removeAll { it.digest == manifestDescriptor.digest }
    layout.syncIndex()

    // Verify blobs still exist on disk
    val fs = FileSystem.SYSTEM
    val configPath =
      "$rootDir/$IMAGE_BLOBS_DIR/${configDescriptor.digest.algorithm}/${configDescriptor.digest.hex}"
        .toPath()
    val layer1Path =
      "$rootDir/$IMAGE_BLOBS_DIR/${layer1Descriptor.digest.algorithm}/${layer1Descriptor.digest.hex}"
        .toPath()
    val layer2Path =
      "$rootDir/$IMAGE_BLOBS_DIR/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}"
        .toPath()
    val manifestPath =
      "$rootDir/$IMAGE_BLOBS_DIR/${manifestDescriptor.digest.algorithm}/${manifestDescriptor.digest.hex}"
        .toPath()

    assertTrue(fs.exists(configPath))
    assertTrue(fs.exists(layer1Path))
    assertTrue(fs.exists(layer2Path))
    assertTrue(fs.exists(manifestPath))

    val removedDigests =
      when (val result = layout.gc()) {
        is OCIResult.Ok -> result.value
        is OCIResult.Err -> throw AssertionError("GC failed: ${result.error}")
      }

    // Verify all blobs were removed since none are referenced in the index
    assertFalse(fs.exists(configPath))
    assertFalse(fs.exists(layer1Path))
    assertFalse(fs.exists(layer2Path))
    assertFalse(fs.exists(manifestPath))

    // Verify all layers were removed
    assertEquals(4, removedDigests.size)
    assertTrue(removedDigests.contains(configDescriptor.digest))
    assertTrue(removedDigests.contains(layer1Descriptor.digest))
    assertTrue(removedDigests.contains(layer2Descriptor.digest))
    assertTrue(removedDigests.contains(manifestDescriptor.digest))
  }

  @Test
  fun `test gc throws exception when layers are being pushed`() = runTest {
    // Create a test blob that will be considered a zombie layer
    val layer1Descriptor =
      createTestBlob("layer1-content", "application/vnd.oci.image.layer.v1.tar+gzip")

    // Create a second blob that we'll simulate as being actively pushed
    val layer2Descriptor =
      createTestBlob("layer2-content", "application/vnd.oci.image.layer.v1.tar+gzip")

    // Verify both layers exist
    assertTrue(layout.exists(layer1Descriptor).getOrNull() == true)
    assertTrue(layout.exists(layer2Descriptor).getOrNull() == true)

    // Get access to the pushing collection via reflection
    val pushingField = Layout::class.java.getDeclaredField("pushing")
    pushingField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val pushing = pushingField.get(layout) as ConcurrentHashMap<Descriptor, Mutex>

    try {
      // Add layer2 to the pushing collection to simulate an active push
      pushing[layer2Descriptor] = Mutex()

      // Verify gc returns an error when there are active pushes
      val result = layout.gc()
      assertTrue(result.isErr())
      val error = result.errorOrNull()
      assertTrue(error is OCIError.Generic)
      assertTrue((error as OCIError.Generic).message.contains("downloads are in progress"))

      // Verify both layers still exist on disk
      val fs = FileSystem.SYSTEM
      val layer1Path =
        "$rootDir/$IMAGE_BLOBS_DIR/${layer1Descriptor.digest.algorithm}/${layer1Descriptor.digest.hex}"
          .toPath()
      val layer2Path =
        "$rootDir/$IMAGE_BLOBS_DIR/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}"
          .toPath()
      assertTrue(fs.exists(layer1Path))
      assertTrue(fs.exists(layer2Path))
      assertTrue(layout.exists(layer1Descriptor).getOrNull() == true)
      assertTrue(layout.exists(layer2Descriptor).getOrNull() == true)
    } finally {
      pushing.remove(layer2Descriptor)
    }
  }

  @Test
  fun `test gc resets download progress`() = runTest {
    // Create a blob file manually to simulate a partial download
    val layerContent = "layer-content"
    val layerBytes = layerContent.toByteArray()
    val layerDigest =
      Digest(
        RegisteredAlgorithm.SHA256,
        RegisteredAlgorithm.SHA256.hasher().apply { update(layerBytes) }.digest(),
      )

    val fs = FileSystem.SYSTEM
    val layerPath = "$rootDir/$IMAGE_BLOBS_DIR/${layerDigest.algorithm}/${layerDigest.hex}".toPath()
    fs.createDirectories(layerPath.parent!!)
    fs.write(layerPath) { write(layerBytes) }

    assertTrue(fs.exists(layerPath))

    // Run gc - this should remove the layer since it's not referenced and not being pushed
    val removedDigests =
      when (val result = layout.gc()) {
        is OCIResult.Ok -> result.value
        is OCIResult.Err -> throw AssertionError("GC failed: ${result.error}")
      }

    // Verify layer was removed
    assertTrue(removedDigests.contains(layerDigest))
    assertFalse(fs.exists(layerPath))

    // This demonstrates that if gc runs between an interrupted download and a retry,
    // it will reset the download progress
  }

  private suspend fun createTestBlob(content: String, mediaType: String): Descriptor {
    val bytes = content.toByteArray()
    val descriptor =
      Descriptor(
        mediaType = mediaType,
        digest =
          Digest(
            RegisteredAlgorithm.SHA256,
            RegisteredAlgorithm.SHA256.hasher().apply { update(bytes) }.digest(),
          ),
        size = bytes.size.toLong(),
      )

    val stream = ByteArrayInputStream(bytes)
    layout.push(descriptor, stream).collect {}

    return descriptor
  }
}
