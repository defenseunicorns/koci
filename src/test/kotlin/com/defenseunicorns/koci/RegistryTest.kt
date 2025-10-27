/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.client.Layout
import com.defenseunicorns.koci.api.client.Registry
import com.defenseunicorns.koci.api.models.CatalogResponse
import com.defenseunicorns.koci.api.models.Descriptor
import com.defenseunicorns.koci.api.models.LayoutMarker
import com.defenseunicorns.koci.api.models.TagsResponse
import com.defenseunicorns.koci.models.ANNOTATION_REF_NAME
import com.defenseunicorns.koci.models.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.models.MANIFEST_CONFIG_MEDIA_TYPE
import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.UserAgent
import com.defenseunicorns.koci.api.models.Digest
import com.defenseunicorns.koci.api.models.Manifest
import com.defenseunicorns.koci.api.models.Reference
import io.ktor.util.Platform
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

const val TEST_BLOB_MEDIATYPE = "application/vnd.koci.test.blob.v1+text"

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RegistryTest {
  companion object {
    val tmp = createTempDirectory("koci-test")

    @OptIn(ExperimentalPathApi::class)
    @JvmStatic
    @AfterAll
    internal fun cleanup() {
      tmp.deleteRecursively()
    }

    val httpClient = HttpClient(CIO) { install(UserAgent) { agent = "unit test client" } }
  }

  private val storage = Layout.create(tmp.toString())!!

  @Test
  fun `layout creation`() {
    assertEquals(emptyList(), storage.catalog())
    assertEquals(
      LayoutMarker("1.0.0"),
      Json.decodeFromString(File("$tmp/$IMAGE_LAYOUT_FILE").readText()),
    )
  }

  private val registry =
    Registry("http://127.0.0.1:5005", client = httpClient) // matches docker-compose.yaml

  @Test
  fun `can ping`() = runTest {
    registry.ping().run {
      assertTrue(this, this.exceptionOrNull().toString())
      assertTrue(this)
    }

    Registry("http://127.0.0.1:5001").ping().run {
      assertTrue(this)
      assertNull(this.getOrNull())
    }
  }

  @Test
  fun `catalog seed repos`() = runTest {
    val result = registry.extensions.catalog()
    assertTrue(result, result.exceptionOrNull()?.message)
    result.repositories.also { repos ->
      val expectedRepos = mutableListOf("dos-games", "library/registry", "test-upload")
      assertContentEquals(expectedRepos, repos)
    }

    val fl = registry.extensions.catalog(1)
    val record = mutableListOf<CatalogResponse>()
    fl.collect { res -> record += res }
    val expected =
      mutableListOf(
        CatalogResponse(repositories = listOf("dos-games")),
        CatalogResponse(repositories = listOf("library/registry")),
        CatalogResponse(repositories = listOf("test-upload")),
      )
    assertContentEquals(expected, record)
  }

  @Test
  fun `list repo tags`() = runTest {
    val result = registry.tags("dos-games")
    assertTrue(result)
    val dosTags = result
    assertEquals(TagsResponse(tags = listOf("1.1.0"), name = "dos-games"), dosTags)
  }

  @Test
  fun `list all`() = runTest {
    val record = registry.extensions.list().toList()

    val all =
      mutableListOf(
        TagsResponse("dos-games", listOf("1.1.0")),
        TagsResponse("library/registry", listOf("2.8.0", "latest")),
        TagsResponse("test-upload", null),
      )

    for ((idx, value) in record.withIndex()) {
      // tags is sometimes a non-deterministic sorted list, or bluefin is just wacky
      assertEquals(all[idx].tags?.sorted(), value.tags?.sorted())
      assertEquals(all[idx].name, value.name)
    }
    assertEquals(all.size, record.size)
  }

  @Test
  fun resolve() = runTest {
    assertEquals(
      INDEX_MEDIA_TYPE,
      registry.repo("dos-games").resolve("1.1.0").mediaType,
    )
    assertEquals(
      MANIFEST_MEDIA_TYPE,
      registry
        .repo("dos-games")
        .resolve("1.1.0") { platform -> platform.os == "multi" }
        
        .mediaType,
    )
    assertFailsWith<OCIException.ManifestNotSupported> {
      registry.repo("library/registry").resolve("2.8.0")
    }
  }

  @Test
  @Suppress("detekt:MaxLineLength")
  fun `fetch a layer`() = runTest {
    val repo = registry.repo("dos-games")
    val manifest =
      repo
        .resolve("1.1.0")
        .map { desc ->
          repo.index(desc).map { repo.manifest(it.manifests.first()) }
        }
        
    val p = repo.pull(manifest.config, storage)

    assertEquals(manifest.config.size.toInt(), p.last())

    val config: String = repo.fetch(manifest.config) { it.bufferedReader().readText() }
    assertEquals(
      "{\"architecture\":\"amd64\",\"ociVersion\":\"1.1.0\",\"annotations\":{\"org.opencontainers.image.description\":\"Simple example to load classic DOS games into K8s in the airgap\",\"org.opencontainers.image.title\":\"dos-games\"}}",
      config,
    )

    assertEquals(true, storage.exists(manifest.config))

    assertEquals(true, storage.remove(manifest.config))

    assertEquals(false, storage.exists(manifest.config))
  }

  @Test
  fun `multiple concurrent pushes of the same content`() = runTest {
    val desc =
      Descriptor(
        mediaType = "blob",
        size = 12,
        digest = Digest("sha256:7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069"),
        annotations = mutableMapOf(),
      )

    val stream = "Hello World!".byteInputStream()

    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    dispatcher.use { d ->
      val d1 = async(d) { storage.push(desc, stream).collect() }
      val d2 = async(d) { storage.push(desc, stream).collect() }
      awaitAll(d1, d2)
    }

    val ok = storage.exists(desc)

    assertTrue(ok)
  }

  @Test
  fun `fetch a layer, cancelling multiple times`() = runTest {
    val repo = registry.repo("dos-games")
    val desc = repo.resolve("1.1.0")
    val layer =
      repo
        .index(desc)
        .map { index ->
          repo.manifest(index.manifests.first()).layers.maxBy { it.size }
        }
        

    val cancelAtBytes = listOf(layer.size.toInt() / 4, layer.size.toInt() / 2, -100)

    assertFalse { storage.exists(layer).getOrDefault(false) }

    for (at in cancelAtBytes) {
      var bytesPulled = 0
      launch {
          repo
            .pull(layer, storage)
            .onCompletion { e ->
              if (at == -100) {
                assertNull(e)
                assertEquals(layer.size.toInt(), bytesPulled)
              } else {
                assertIs<CancellationException>(e)
              }
            }
            .collect { progress ->
              bytesPulled += progress
              if (at in 1..bytesPulled) cancel()
            }
        }
        .join()

      storage
        .exists(layer)
        .fold(
          onSuccess = { assertEquals(bytesPulled, layer.size.toInt()) },
          onFailure = { e ->
            assertIs<OCIException.SizeMismatch>(e)
            // bytesPulled will be an unreliable number during cancellations and should not be
            // relied upon
            assertTrue(bytesPulled <= e.actual.toInt())
          },
        )
    }

    assertEquals(true, storage.exists(layer))

    assertEquals(true, storage.remove(layer))

    assertEquals(false, storage.exists(layer))
  }

  @OptIn(ExperimentalSerializationApi::class)
  @Test
  fun `pull and remove dos-games`() = runTest {
    val indexDesc = registry.resolve("dos-games", "1.1.0")
    val index = registry.repo("dos-games").index(indexDesc)
    val prog = registry.pull("dos-games", "1.1.0", storage)

    assertEquals(100, prog.last())

    val ref = Reference.parse("127.0.0.1:5005/dos-games:1.1.0")
    assertEquals(indexDesc.digest, storage.resolve(ref).digest)
    assertEquals(
      listOf(indexDesc.copy(annotations = mapOf(ANNOTATION_REF_NAME to ref.toString()))),
      storage.catalog(),
    )

    val arm64desc = index.manifests.first { it.platform?.architecture == "arm64" }
    val arm64Manifest: Manifest = storage.fetch(arm64desc).use { Json.decodeFromStream(it) }
    assertTrue(storage.remove(ref))
    assertFails { storage.resolve(ref) }
    assertEquals(emptyList(), storage.catalog())

    for (layer in arm64Manifest.layers) {
      assertFalse { storage.exists(layer) }
    }
    assertFalse { storage.exists(arm64Manifest.config) }
  }

  @Test
  @EnabledIfSystemProperty(named = "TESTS_WITH_EXTERNAL_SERVICES", matches = "true")
  fun `pull and remove gradle from dockerhub`() =
    runTest(timeout = 10.minutes) {
      val registry = Registry.create("https://registry-1.docker.io")
      val prog = registry.pull("library/gradle", "latest", storage)

      assertEquals(100, prog.last())

      assertTrue(
        storage
          .remove(Reference.parse("registry-1.docker.io/library/gradle:latest"))
      )
    }

  @Test
  fun `resume-able pulls`() = runTest {
    val desc = registry.resolve("dos-games", "1.1.0")
    val amd64Resolver = { plat: Platform -> plat.architecture == "amd64" && plat.os == "multi" }

    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    val cancelPoints = listOf(5, 15, 50, -100)

    dispatcher.use { d ->
      for (cancelAt in cancelPoints) {
        var lastProgress = 0

        val pullJob =
          async(d) {
            try {
              registry.pull("dos-games", "1.1.0", storage, amd64Resolver).collect { progress ->
                lastProgress = progress
                if (cancelAt == progress) {
                  throw CancellationException("download cancelled at $cancelAt")
                }
              }
              // Success case
              assertNull(null)
              assertEquals(100, lastProgress)
            } catch (e: CancellationException) {
              // Cancellation case
              assertEquals("download cancelled at $cancelAt", e.message)
              assertFailsWith<NoSuchElementException> {
                storage.resolve { it.digest == desc.digest }
              }
            }
          }

        pullJob.await()
      }
    }

    assertTrue(storage.remove(desc))
  }

  @Test
  fun `concurrent pulls + removals`() {
    assertDoesNotThrow {
      runTest(timeout = kotlin.time.Duration.parse("PT30S")) {
        val p1 = async { registry.pull("dos-games", "1.1.0", storage).collect() }
        val p2 = async { registry.pull("dos-games", "1.1.0", storage).collect() }
        val p3 = async { registry.pull("library/registry", "latest", storage).collect() }
        awaitAll(p1, p2, p3)

        val d1 = registry.resolve("dos-games", "1.1.0")
        assertTrue(storage.exists(d1))
        val d2 = registry.resolve("library/registry", "latest")
        assertTrue(storage.exists(d2))

        storage.remove(d2)
      }
    }

    assertDoesNotThrow {
      runTest(timeout = kotlin.time.Duration.parse("PT30S")) {
        val descriptor = registry.resolve("dos-games", "1.1.0")!!
        assertTrue(storage.exists(descriptor))

        val r1 = async { storage.remove(descriptor) }
        val r2 = async { storage.remove(descriptor) }
        awaitAll(r1, r2)

        assertFalse(storage.exists(descriptor))
      }
    }
  }

  @Test
  @Order(1)
  fun `upload a layer and tag an artifact`() = runTest {
    val stream = "Hello World!".byteInputStream()

    val desc = Descriptor.fromInputStream(mediaType = TEST_BLOB_MEDIATYPE, stream = stream)!!

    stream.reset()

    val repo = registry.repo("test-upload")

    assertEquals(desc.size, repo.push(stream, desc).last())

    assertTrue { repo.exists(desc) }

    val tmp11 = tmp.resolve("11mb.txt").absolutePathString()

    /**
     * 11mb causes a POST, PATCH, PATCH, PUT 10mb only causes a POST, PATCH, PUT
     *
     * Ensures that offsets are calculated correctly on PATCH
     */
    val tmp11Desc = generateRandomFile(tmp11, 11 * 1024 * 1024)

    repo.push(File(tmp11).inputStream(), tmp11Desc).collect()

    assertTrue { repo.exists(tmp11Desc) }
    assertTrue { repo.remove(desc) }
    assertFailsWith<ClientRequestException> { repo.exists(desc) }
    assertTrue { repo.remove(tmp11Desc) }
    assertFailsWith<ClientRequestException> { !repo.exists(tmp11Desc) }

    val tmp15 = tmp.resolve("15mb.txt").absolutePathString()
    val tmp15Desc = generateRandomFile(tmp15, 15 * 1024 * 1024 + 300)

    val cancelAt = listOf(33L, 66L)

    for (at in cancelAt) {
      launch {
          repo
            .push(File(tmp15).inputStream(), tmp15Desc)
            .onCompletion { e ->
              assertIs<CancellationException>(e)
              assertTrue { repo.exists(tmp15Desc) }
            }
            .collect { progress ->
              val percent = progress * 100 / tmp15Desc.size
              if (at == percent) cancel()
            }
        }
        .join()
    }
    val last = repo.push(File(tmp15).inputStream(), tmp15Desc).last()
    assertEquals(tmp15Desc.size, last)

    assertTrue { repo.exists(tmp15Desc) }
    assertTrue { repo.remove(tmp15Desc) }

    // now push a layer and tag the artifact
    stream.reset()

    repo.push(stream, desc).collect()

    val dummy = "{}".byteInputStream()

    val dummyDesc =
      Descriptor.fromInputStream(mediaType = MANIFEST_CONFIG_MEDIA_TYPE, stream = dummy)!!

    dummy.reset()

    // config must be pushed before repo is tagged
    repo.push(dummy, dummyDesc).collect()

    val manifest =
      Manifest(
        schemaVersion = 2,
        mediaType = MANIFEST_MEDIA_TYPE,
        config = dummyDesc,
        layers = listOf(desc),
      )

    repo.tag(manifest, "latest").also { res ->
      val manifestDesc = res
      assertTrue { repo.remove(manifestDesc) }
    }
  }

  @Test
  @Order(2)
  fun `mounting blobs from another repository`() = runTest {
    val sourceRepo = registry.repo("dos-games")
    val targetRepo = registry.repo("test-upload")

    // Get an existing blob from the source repository
    val indexDesc = registry.resolve("dos-games", "1.1.0")!!
    val index = sourceRepo.index(indexDesc)

    // First get a manifest, then use its layers
    val manifestDesc = index.manifests.first()
    val manifest = sourceRepo.manifest(manifestDesc)
    val existingBlob = manifest.layers.first()

    // Case 1: Blob does not exist in source repo, but can be pushed afterwards
    val nonExistentContent = "This blob doesn't exist yet".byteInputStream()
    val nonExistentDesc =
      Descriptor.fromInputStream(mediaType = TEST_BLOB_MEDIATYPE, stream = nonExistentContent)!!
    nonExistentContent.reset()

    val nonExistentResult = targetRepo.mount(nonExistentDesc, "dos-games")
    assertFalse(nonExistentResult, "Mount should fail for non-existent blob")

    val pushResult = targetRepo.push(nonExistentContent, nonExistentDesc).last()
    assertEquals(nonExistentDesc.size, pushResult, "Push after failed mount should succeed")

    assertTrue(targetRepo.exists(nonExistentDesc), "Blob should exist after push")

    assertTrue(targetRepo.remove(nonExistentDesc), "Blob removal should succeed")

    // Case 2: Mount a blob that already exists in target repo
    val duplicateContent = "This is a duplicate blob".byteInputStream()
    val duplicateDesc =
      Descriptor.fromInputStream(mediaType = TEST_BLOB_MEDIATYPE, stream = duplicateContent)!!
    duplicateContent.reset()

    targetRepo.push(duplicateContent, duplicateDesc).collect()

    val duplicateResult = targetRepo.mount(duplicateDesc, "dos-games")
    assertTrue(duplicateResult, "Mount should report success for blob that already exists")

    assertTrue(targetRepo.remove(duplicateDesc), "Blob removal should succeed")

    // Case 3: Successfully mount a blob from source repo
    assertTrue(sourceRepo.exists(existingBlob), "Blob should exist in source repo")
    assertFalse(
      targetRepo.exists(existingBlob),
      "Blob should not exist in target repo yet",
    )

    val mountResult = targetRepo.mount(existingBlob, "dos-games")
    assertTrue(mountResult, "Mount should succeed for existing blob")

    assertTrue(
      targetRepo.exists(existingBlob),
      "Blob should exist in target repo after mount",
    )

    assertTrue(targetRepo.remove(existingBlob), "Blob removal should succeed")
  }

  @Test
  fun `public scopes`() = runTest {
    val ecr = Registry("https://public.ecr.aws", client = httpClient)

    val result = ecr.repo("ubuntu/redis").tags()
    assertTrue(result, result.exceptionOrNull().toString())

    val nvcr = Registry("https://nvcr.io", client = httpClient)

    nvcr.tags("nvidia/l4t-pytorch")
  }
}

fun generateRandomFile(filePath: String, sizeInBytes: Int): Descriptor {
  val random = Random.Default
  val buffer = ByteArray(1024) // 1KB buffer

  FileOutputStream(File(filePath)).use { outputStream ->
    var bytesWritten = 0
    while (bytesWritten < sizeInBytes) {
      random.nextBytes(buffer) // Fill buffer with random data
      val bytesToWrite = minOf(buffer.size, sizeInBytes - bytesWritten)
      outputStream.write(buffer, 0, bytesToWrite)
      bytesWritten += bytesToWrite
    }
  }

  return Descriptor.fromInputStream(
    mediaType = TEST_BLOB_MEDIATYPE,
    stream = File(filePath).inputStream(),
  )!!
}
