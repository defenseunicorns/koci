/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.models.Descriptor
import com.defenseunicorns.koci.models.Digest
import com.defenseunicorns.koci.models.IMAGE_BLOBS_DIR
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import com.defenseunicorns.koci.models.Manifest
import com.defenseunicorns.koci.models.Reference
import com.defenseunicorns.koci.models.RegisteredAlgorithm
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class LayoutTest {

  @TempDir lateinit var tempDir: Path

  private lateinit var layout: Layout
  private lateinit var rootDir: String

  @BeforeEach
  fun setup() = runBlocking {
    rootDir = tempDir.toString()
    layout = Layout.create(rootDir).getOrThrow()
  }

  @AfterEach
  fun cleanup() {
    tempDir.toFile().deleteRecursively()
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

      assertTrue(layout.exists(blob1).getOrThrow())
      assertTrue(layout.exists(blob2).getOrThrow())
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
    layout.tag(manifestDescriptor, reference).getOrThrow()

    // Verify all blobs exist
    assertTrue(layout.exists(configDescriptor).getOrThrow())
    assertTrue(layout.exists(layer1Descriptor).getOrThrow())
    assertTrue(layout.exists(layer2Descriptor).getOrThrow())
    assertTrue(layout.exists(manifestDescriptor).getOrThrow())

    // Verify layer2 is a zombie (not referenced by any manifest)
    val zombieFile =
      File(
        "$rootDir/$IMAGE_BLOBS_DIR/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}"
      )
    assertTrue(zombieFile.exists())

    val removedDigests = layout.gc().getOrThrow()

    // Verify layer2 was removed
    assertFalse(zombieFile.exists())

    // Verify only layer2 was removed
    assertEquals(1, removedDigests.size)
    assertEquals(layer2Descriptor.digest, removedDigests[0])

    // Verify other blobs still exist
    assertTrue(layout.exists(configDescriptor).getOrThrow())
    assertTrue(layout.exists(layer1Descriptor).getOrThrow())
    assertTrue(layout.exists(manifestDescriptor).getOrThrow())
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
    layout.tag(manifestDescriptor, reference).getOrThrow()

    // Verify all blobs exist
    assertTrue(layout.exists(configDescriptor).getOrThrow())
    assertTrue(layout.exists(layer1Descriptor).getOrThrow())
    assertTrue(layout.exists(layer2Descriptor).getOrThrow())
    assertTrue(layout.exists(manifestDescriptor).getOrThrow())

    // Simulate an interrupted remove operation by manually removing the manifest from the index
    // but leaving the blobs on disk
    layout.index.manifests.removeAll { it.digest == manifestDescriptor.digest }
    layout.syncIndex()

    // Verify blobs still exist on disk
    val configFile =
      File(
        "$rootDir/$IMAGE_BLOBS_DIR/${configDescriptor.digest.algorithm}/${configDescriptor.digest.hex}"
      )
    val layer1File =
      File(
        "$rootDir/$IMAGE_BLOBS_DIR/${layer1Descriptor.digest.algorithm}/${layer1Descriptor.digest.hex}"
      )
    val layer2File =
      File(
        "$rootDir/$IMAGE_BLOBS_DIR/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}"
      )
    val manifestFile =
      File(
        "$rootDir/$IMAGE_BLOBS_DIR/${manifestDescriptor.digest.algorithm}/${manifestDescriptor.digest.hex}"
      )

    assertTrue(configFile.exists())
    assertTrue(layer1File.exists())
    assertTrue(layer2File.exists())
    assertTrue(manifestFile.exists())

    val removedDigests = layout.gc().getOrThrow()

    // Verify all blobs were removed since none are referenced in the index
    assertFalse(configFile.exists())
    assertFalse(layer1File.exists())
    assertFalse(layer2File.exists())
    assertFalse(manifestFile.exists())

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
    assertTrue(layout.exists(layer1Descriptor).getOrThrow())
    assertTrue(layout.exists(layer2Descriptor).getOrThrow())

    // Get access to the pushing collection via reflection
    val pushingField = Layout::class.java.getDeclaredField("pushing")
    pushingField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val pushing = pushingField.get(layout) as ConcurrentHashMap<Descriptor, Mutex>

    try {
      // Add layer2 to the pushing collection to simulate an active push
      pushing[layer2Descriptor] = Mutex()

      // Verify gc throws an IllegalStateException when there are active pushes
      val exception = assertThrows<IllegalStateException> { layout.gc().getOrThrow() }

      // Verify the exception message
      assertEquals("there are downloads in progress", exception.message)

      // Verify both layers still exist on disk
      val layer1File =
        File(
          "$rootDir/$IMAGE_BLOBS_DIR/${layer1Descriptor.digest.algorithm}/${layer1Descriptor.digest.hex}"
        )
      val layer2File =
        File(
          "$rootDir/$IMAGE_BLOBS_DIR/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}"
        )
      assertTrue(layer1File.exists())
      assertTrue(layer2File.exists())
      assertTrue(layout.exists(layer1Descriptor).getOrThrow())
      assertTrue(layout.exists(layer2Descriptor).getOrThrow())
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

    val layerFile = File("$rootDir/$IMAGE_BLOBS_DIR/${layerDigest.algorithm}/${layerDigest.hex}")
    layerFile.parentFile.mkdirs()
    layerFile.writeBytes(layerBytes)

    assertTrue(layerFile.exists())

    // Run gc - this should remove the layer since it's not referenced and not being pushed
    val removedDigests = layout.gc().getOrThrow()

    // Verify layer was removed
    assertTrue(removedDigests.contains(layerDigest))
    assertFalse(layerFile.exists())

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
