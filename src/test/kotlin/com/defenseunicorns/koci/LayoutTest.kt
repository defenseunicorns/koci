/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class LayoutTest {

    @TempDir
    lateinit var tempDir: Path

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
    fun `test gc removes zombie layers`() = runTest {
        val configDescriptor = createTestBlob("config-content", "application/vnd.oci.image.config.v1+json")
        val layer1Descriptor = createTestBlob("layer1-content", "application/vnd.oci.image.layer.v1.tar+gzip")
        val layer2Descriptor = createTestBlob("layer2-content", "application/vnd.oci.image.layer.v1.tar+gzip")

        // Create a manifest that references config and layer1
        val manifest = Manifest(
            schemaVersion = 2,
            mediaType = MANIFEST_MEDIA_TYPE,
            config = configDescriptor,
            layers = listOf(layer1Descriptor),
            annotations = null
        )

        val manifestJson = Json.encodeToString(manifest)
        val manifestStream = ByteArrayInputStream(manifestJson.toByteArray()).toByteReadChannel()
        val manifestDescriptor = Descriptor(
            mediaType = MANIFEST_MEDIA_TYPE,
            digest = Digest(RegisteredAlgorithm.SHA256, RegisteredAlgorithm.SHA256.hasher().apply {
                update(manifestJson.toByteArray())
            }.digest()),
            size = manifestJson.length.toLong()
        )

        layout.push(manifestDescriptor, manifestStream).collect { }

        val reference = Reference(registry = "localhost", repository = "test", reference = "latest")
        layout.tag(manifestDescriptor, reference).getOrThrow()

        // Verify all blobs exist
        assertTrue(layout.exists(configDescriptor).getOrThrow())
        assertTrue(layout.exists(layer1Descriptor).getOrThrow())
        assertTrue(layout.exists(layer2Descriptor).getOrThrow())
        assertTrue(layout.exists(manifestDescriptor).getOrThrow())

        // Verify layer2 is a zombie (not referenced by any manifest)
        val zombieFile = File("$rootDir/blobs/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}")
        assertTrue(zombieFile.exists())

        val prunedLayers = layout.gc().getOrThrow()

        // Verify layer2 was removed
        assertFalse(zombieFile.exists())

        // Verify only layer2 was removed
        assertEquals(1, prunedLayers.size)
        assertEquals(layer2Descriptor.digest, prunedLayers[0])

        // Verify other blobs still exist
        assertTrue(layout.exists(configDescriptor).getOrThrow())
        assertTrue(layout.exists(layer1Descriptor).getOrThrow())
        assertTrue(layout.exists(manifestDescriptor).getOrThrow())
    }

    @Test
    fun `test gc with interrupted remove operation`() = runTest {
        val configDescriptor = createTestBlob("config-content", "application/vnd.oci.image.config.v1+json")
        val layer1Descriptor = createTestBlob("layer1-content", "application/vnd.oci.image.layer.v1.tar+gzip")
        val layer2Descriptor = createTestBlob("layer2-content", "application/vnd.oci.image.layer.v1.tar+gzip")

        // Create a manifest that references config, layer1 and layer2
        val manifest = Manifest(
            schemaVersion = 2,
            mediaType = MANIFEST_MEDIA_TYPE,
            config = configDescriptor,
            layers = listOf(layer1Descriptor, layer2Descriptor),
            annotations = null
        )

        val manifestJson = Json.encodeToString(manifest)
        val manifestStream = ByteArrayInputStream(manifestJson.toByteArray()).toByteReadChannel()
        val manifestDescriptor = Descriptor(
            mediaType = MANIFEST_MEDIA_TYPE,
            digest = Digest(RegisteredAlgorithm.SHA256, RegisteredAlgorithm.SHA256.hasher().apply {
                update(manifestJson.toByteArray())
            }.digest()),
            size = manifestJson.length.toLong()
        )

        layout.push(manifestDescriptor, manifestStream).collect { }

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
        val configFile = File("$rootDir/blobs/${configDescriptor.digest.algorithm}/${configDescriptor.digest.hex}")
        val layer1File = File("$rootDir/blobs/${layer1Descriptor.digest.algorithm}/${layer1Descriptor.digest.hex}")
        val layer2File = File("$rootDir/blobs/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}")
        val manifestFile =
            File("$rootDir/blobs/${manifestDescriptor.digest.algorithm}/${manifestDescriptor.digest.hex}")

        assertTrue(configFile.exists())
        assertTrue(layer1File.exists())
        assertTrue(layer2File.exists())
        assertTrue(manifestFile.exists())

        val prunedLayers = layout.gc().getOrThrow()

        // Verify all blobs were removed since none are referenced in the index
        assertFalse(configFile.exists())
        assertFalse(layer1File.exists())
        assertFalse(layer2File.exists())
        assertFalse(manifestFile.exists())

        // Verify all layers were removed
        assertEquals(4, prunedLayers.size)
        assertTrue(prunedLayers.contains(configDescriptor.digest))
        assertTrue(prunedLayers.contains(layer1Descriptor.digest))
        assertTrue(prunedLayers.contains(layer2Descriptor.digest))
        assertTrue(prunedLayers.contains(manifestDescriptor.digest))
    }

    @Test
    fun `test gc does not remove layers being pushed`() = runTest {
        // Create a test blob that will be considered a zombie layer
        val layer1Descriptor = createTestBlob("layer1-content", "application/vnd.oci.image.layer.v1.tar+gzip")
        
        // Create a second blob that we'll simulate as being actively pushed
        val layer2Descriptor = createTestBlob("layer2-content", "application/vnd.oci.image.layer.v1.tar+gzip")
        
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
            
            val removedLayers = layout.gc().getOrThrow()
            
            // Verify layer1 was removed (it's not referenced by any manifest and not being pushed)
            assertEquals(1, removedLayers.size)
            assertEquals(layer1Descriptor.digest, removedLayers[0])
            
            // Verify layer1 no longer exists on disk
            val layer1File = File("$rootDir/blobs/${layer1Descriptor.digest.algorithm}/${layer1Descriptor.digest.hex}")
            assertFalse(layer1File.exists())
            
            // Verify layer2 was NOT removed (it's being pushed)
            val layer2File = File("$rootDir/blobs/${layer2Descriptor.digest.algorithm}/${layer2Descriptor.digest.hex}")
            assertTrue(layer2File.exists())
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
        val layerDigest = Digest(RegisteredAlgorithm.SHA256, RegisteredAlgorithm.SHA256.hasher().apply {
            update(layerBytes)
        }.digest())
        
        val layerFile = File("$rootDir/blobs/${layerDigest.algorithm}/${layerDigest.hex}")
        layerFile.parentFile.mkdirs()
        layerFile.writeBytes(layerBytes)
        
        assertTrue(layerFile.exists())
        
        // Run gc - this should remove the layer since it's not referenced and not being pushed
        val removedLayers = layout.gc().getOrThrow()
        
        // Verify layer was removed
        assertTrue(removedLayers.contains(layerDigest))
        assertFalse(layerFile.exists())
        
        // This demonstrates that if gc runs between an interrupted download and a retry,
        // it will reset the download progress
    }

    private suspend fun createTestBlob(content: String, mediaType: String): Descriptor {
        val bytes = content.toByteArray()
        val descriptor = Descriptor(
            mediaType = mediaType,
            digest = Digest(RegisteredAlgorithm.SHA256, RegisteredAlgorithm.SHA256.hasher().apply {
                update(bytes)
            }.digest()),
            size = bytes.size.toLong()
        )

        val stream = ByteArrayInputStream(bytes).toByteReadChannel()
        layout.push(descriptor, stream).collect { }

        return descriptor
    }
}
