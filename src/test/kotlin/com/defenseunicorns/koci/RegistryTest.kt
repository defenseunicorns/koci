/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.junit.jupiter.api.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.*
import kotlin.test.Test

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

        val httpClient = HttpClient(CIO) {
            install(UserAgent) {
                agent = "unit test client"
            }
        }
    }

    private val storage = runBlocking {
        Layout.create(tmp.toString())
    }.getOrThrow()

    @Test
    fun `layout creation`() {
        assertEquals(emptyList(), storage.catalog())
        assertEquals(LayoutMarker("1.0.0"), Json.decodeFromString(File("$tmp/oci-layout").readText()))
    }

    private val registry = Registry("http://127.0.0.1:5005", httpClient) // matches docker-compose.yaml

    @Test
    fun `can ping`() = runTest {
        registry.ping().run {
            assertTrue(this.isSuccess, this.exceptionOrNull().toString())
            assertTrue(this.getOrThrow())
        }

        Registry("http://127.0.0.1:5001").ping().run {
            assertTrue(this.isFailure)
            assertNull(this.getOrNull())
        }
    }

    @Test
    fun `catalog seed repos`() = runTest {
        val result = registry.extensions.catalog()
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        result.getOrThrow().repositories.also { repos ->
            val expectedRepos = mutableListOf(
                "dos-games", "library/registry", "test-upload"
            )
            assertContentEquals(
                expectedRepos, repos
            )
        }

        val fl = registry.extensions.catalog(1)
        val record = mutableListOf<CatalogResponse>()
        fl.collect { res ->
            assertTrue(res.isSuccess, res.exceptionOrNull()?.message)
            record += res.getOrThrow()
        }
        val expected = mutableListOf(
            CatalogResponse(repositories = listOf("dos-games")),
            CatalogResponse(repositories = listOf("library/registry")),
            CatalogResponse(repositories = listOf("test-upload")),
        )
        assertContentEquals(
            expected, record
        )
    }

    @Test
    fun `list repo tags`() = runTest {
        val result = registry.tags("dos-games")
        assertTrue(result.isSuccess)
        val dosTags = result.getOrThrow()
        assertEquals(
            TagsResponse(
                tags = listOf("1.1.0"), name = "dos-games"
            ), dosTags
        )
    }

    @Test
    fun `list all`() = runTest {
        val record: MutableList<Result<TagsResponse>> = mutableListOf()
        registry.extensions.list().toList(record)

        val all = mutableListOf(
            TagsResponse("dos-games", listOf("1.1.0")),
            TagsResponse("library/registry", listOf("2.8.0", "latest")),
            TagsResponse("test-upload", null),
        )

        for ((idx, value) in record.withIndex()) {
            assertTrue(value.isSuccess, value.exceptionOrNull()?.message)
            // tags is sometimes a non-deterministic sorted list, or bluefin is just wacky
            assertEquals(all[idx].tags?.sorted(), value.getOrThrow().tags?.sorted())
            assertEquals(all[idx].name, value.getOrThrow().name)
        }
        assertEquals(all.size, record.size)
    }

    @Test
    fun resolve() = runTest {
        assertEquals(INDEX_MEDIA_TYPE, registry.repo("dos-games").resolve("1.1.0").getOrThrow().mediaType)
        assertEquals(MANIFEST_MEDIA_TYPE, registry.repo("dos-games").resolve("1.1.0") { platform ->
            platform.os == "multi"
        }.getOrThrow().mediaType)
        assertFailsWith<OCIException.ManifestNotSupported>{
            registry.repo("library/registry").resolve("2.8.0").getOrThrow()
        }
    }

    @Test
    @Suppress("detekt:MaxLineLength")
    fun `fetch a layer`() = runTest {
        val repo = registry.repo("dos-games")
        val manifest = repo.resolve("1.1.0")
            .map { desc -> repo.index(desc).map { repo.manifest(it.manifests.first()).getOrThrow() }.getOrThrow() }
            .getOrThrow()
        val p = repo.pull(
            manifest.config, storage
        )

        assertEquals(
            manifest.config.size.toInt(), p.last()
        )

        val config: String = repo.fetch(manifest.config) {
            it.bufferedReader().readText()
        }
        assertEquals(
            "{\"architecture\":\"amd64\",\"ociVersion\":\"1.1.0\",\"annotations\":{\"org.opencontainers.image.description\":\"Simple example to load classic DOS games into K8s in the airgap\",\"org.opencontainers.image.title\":\"dos-games\"}}",
            config
        )

        assertEquals(
            true, storage.exists(manifest.config).getOrThrow()
        )

        assertEquals(
            true, storage.remove(manifest.config).getOrThrow()
        )

        assertEquals(false, storage.exists(manifest.config).getOrThrow())
    }

    @Test
    fun `multiple concurrent pushes of the same content`() = runTest {
        val desc = Descriptor(
            mediaType = "blob",
            size = 12,
            digest = Digest("sha256:7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069"),
            annotations = mutableMapOf()
        )

        val stream = ByteReadChannel("Hello World!")

        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

        dispatcher.use { d ->
            val d1 = async(d) {
                storage.push(desc, stream).collect()
            }
            val d2 = async(d) {
                storage.push(desc, stream).collect()
            }
            awaitAll(d1, d2)
        }

        val ok = storage.exists(desc)

        assertTrue(ok.getOrThrow())
    }

    @Test
    fun `fetch a layer, cancelling multiple times`() = runTest {
        val repo = registry.repo("dos-games")
        val desc = repo.resolve("1.1.0").getOrThrow()
        val layer = repo.index(desc).map { index ->
            repo.manifest(index.manifests.first()).getOrThrow().layers.maxBy { it.size }
        }.getOrThrow()

        val cancelAtBytes = listOf(layer.size.toInt() / 4, layer.size.toInt() / 2, -100)

        assertFalse { storage.exists(layer).getOrDefault(false) }

        for (at in cancelAtBytes) {
            var bytesPulled = 0
            launch {
                repo.pull(layer, storage).onCompletion { e ->
                    if (at == -100) {
                        assertNull(e)
                        assertEquals(layer.size.toInt(), bytesPulled)
                    } else {
                        assertIs<CancellationException>(e)
                    }
                }.collect { progress ->
                    bytesPulled += progress
                    if (at in 1..bytesPulled) cancel()
                }
            }.join()

            storage.exists(layer).fold(onSuccess = {
                assertEquals(bytesPulled, layer.size.toInt())
            }, onFailure = { e ->
                assertIs<OCIException.SizeMismatch>(e)
                // bytesPulled will be an unreliable number during cancellations and should not be relied upon
                assertTrue(bytesPulled <= e.actual.toInt())
            })
        }

        assertEquals(
            true, storage.exists(layer).getOrThrow()
        )

        assertEquals(
            true, storage.remove(layer).getOrThrow()
        )

        assertEquals(false, storage.exists(layer).getOrThrow())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `pull and remove dos-games`() = runTest {
        val indexDesc = registry.resolve("dos-games", "1.1.0").getOrThrow()
        val index = registry.repo("dos-games").index(indexDesc).getOrThrow()
        val prog = registry.pull("dos-games", "1.1.0", storage)

        assertEquals(
            100, prog.last()
        )

        val ref = Reference.parse("127.0.0.1:5005/dos-games:1.1.0").getOrThrow()
        assertEquals(indexDesc.digest, storage.resolve(ref).getOrThrow().digest)
        assertEquals(
            listOf(indexDesc.copy(annotations = mapOf(ANNOTATION_REF_NAME to ref.toString()))),
            storage.catalog()
        )

        val arm64desc = index.manifests.first {
            it.platform?.architecture == "arm64"
        }
        val arm64Manifest: Manifest = storage.fetch(arm64desc).use { Json.decodeFromStream(it) }
        assertTrue(storage.remove(ref).getOrThrow())
        assertFails { storage.resolve(ref).getOrThrow() }
        assertEquals(emptyList(), storage.catalog())

        for (layer in arm64Manifest.layers) {
            assertFalse {
                storage.exists(layer).getOrThrow()
            }
        }
        assertFalse {
            storage.exists(arm64Manifest.config).getOrThrow()
        }
    }

    @Test
    fun `resume-able pulls`() = runTest {
        val desc = registry.resolve("dos-games", "1.1.0").getOrThrow()
        val amd64Resolver = { plat: Platform ->
            plat.architecture == "amd64" && plat.os == "multi"
        }
        
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val cancelPoints = listOf(5, 15, 50, -100)

        dispatcher.use { d ->
            for (cancelAt in cancelPoints) {
                var lastProgress = 0
                
                val pullJob = async(d) {
                    try {
                        registry.pull("dos-games", "1.1.0", storage, amd64Resolver)
                            .collect { progress ->
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
                            storage.resolve { it.digest == desc.digest }.getOrThrow()
                        }
                    }
                }
                
                pullJob.await()
            }
        }

        assertTrue(storage.remove(desc).getOrThrow())
    }

    @Test
    fun `concurrent pulls + removals`() {
        assertDoesNotThrow {
            runTest(timeout = kotlin.time.Duration.parse("PT2M")) {
                val p1 = async {
                    registry.pull("dos-games", "1.1.0", storage).collect()
                }
                val p2 = async {
                    registry.pull("library/registry", "latest", storage).collect()
                }
                awaitAll(p1, p2)
            }
        }

        assertDoesNotThrow {
            runTest {
                val d1 = registry.resolve("dos-games", "1.1.0").getOrThrow()
                val r1 = async {
                    storage.remove(d1).getOrThrow()
                }
                val d2 = registry.resolve("library/registry", "latest").getOrThrow()
                val r2 = async {
                    storage.remove(d2).getOrThrow()
                }
                awaitAll(r1, r2)
            }
        }
    }

    @Test
    @Order(1)
    fun `upload a layer and tag an artifact`() = runTest {
        val stream = "Hello World!".byteInputStream()

        val desc = Descriptor.fromInputStream(mediaType = TEST_BLOB_MEDIATYPE, stream = stream)

        stream.reset()

        val repo = registry.repo("test-upload")

        assertEquals(
            desc.size, repo.push(stream, desc).last()
        )

        assertTrue { repo.exists(desc).getOrThrow() }

        val tmp10 = tmp.resolve("10mb.txt").absolutePathString()

        val tmp10Desc = generateRandomFile(tmp10, 10 * 1024 * 1024)

        repo.push(File(tmp10).inputStream(), tmp10Desc).collect()

        assertTrue { repo.exists(tmp10Desc).getOrThrow() }
        assertTrue { repo.remove(desc).getOrThrow() }
        assertTrue { repo.remove(tmp10Desc).getOrThrow() }

        val tmp15 = tmp.resolve("15mb.txt").absolutePathString()
        val tmp15Desc = generateRandomFile(tmp15, 15 * 1024 * 1024 + 300)

        val cancelAt = listOf(33L, 66L)

        for (at in cancelAt) {
            launch {
                repo.push(File(tmp15).inputStream(), tmp15Desc).onCompletion { e ->
                    assertIs<CancellationException>(e)
                    assertTrue {
                        repo.exists(tmp15Desc).isFailure
                    }
                }.collect { progress ->
                    val percent = progress * 100 / tmp15Desc.size
                    if (at == percent) cancel()
                }
            }.join()
        }
        val last = repo.push(File(tmp15).inputStream(), tmp15Desc).last()
        assertEquals(tmp15Desc.size, last)

        assertTrue { repo.exists(tmp15Desc).getOrThrow() }
        assertTrue { repo.remove(tmp15Desc).getOrThrow() }

        // now push a layer and tag the artifact
        stream.reset()

        repo.push(stream, desc).collect()

        val dummy = "{}".byteInputStream()

        val dummyDesc = Descriptor.fromInputStream(
            mediaType = MANIFEST_CONFIG_MEDIA_TYPE, stream = dummy
        )

        dummy.reset()

        // config must be pushed before repo is tagged
        repo.push(dummy, dummyDesc).collect()

        val manifest = Manifest(
            schemaVersion = 2, mediaType = MANIFEST_MEDIA_TYPE, config = dummyDesc, layers = listOf(desc)
        )

        repo.tag(manifest, "latest").also { res ->
            val manifestDesc = res.getOrThrow()
            assertTrue {
                repo.remove(manifestDesc).getOrThrow()
            }
        }
    }

    @Test
    fun `public scopes`() = runTest {
        val ecr = Registry("https://public.ecr.aws", httpClient)

        val result = ecr.repo("ubuntu/redis").tags()
        assertTrue(result.isSuccess, result.exceptionOrNull().toString())

        val nvcr = Registry("https://nvcr.io", httpClient)

        nvcr.tags("nvidia/l4t-pytorch").getOrThrow()
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

    return Descriptor.fromInputStream(mediaType = TEST_BLOB_MEDIATYPE, stream = File(filePath).inputStream())
}
