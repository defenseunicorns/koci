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

    private val currentArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "amd64"

    private fun zarfResolver(platform: Platform): Boolean {
        return platform.architecture == currentArch && platform.os == MULTI_OS
    }

    private val registry =
        Registry.Builder().registryURL("http://127.0.0.1:5005").storage(storage) // matches docker-compose.yaml
            .client(httpClient).build()

    @Test
    fun `can ping`() = runTest {
        val result = registry.ping()
        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
        assertTrue(result.getOrThrow())

        val badRegistry =
            Registry.Builder().registryURL("http://127.0.0.1:5001").storage(storage).client(httpClient).build()

        val badResult = badRegistry.ping()
        assertTrue(badResult.isFailure)
        assertEquals(null, badResult.getOrNull())
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
            TagsResponse("library/registry", listOf("latest", "2.8.0")),
            TagsResponse("test-upload", null),
        )

        for ((idx, value) in record.withIndex()) {
            assertTrue(value.isSuccess, value.exceptionOrNull()?.message)
            assertEquals(all[idx], value.getOrThrow())
        }
        assertEquals(all.size, record.size)
    }

    @Test
    fun resolve() = runTest {
        val result = registry.repo("dos-games").resolve("1.1.0", ::zarfResolver)
        assertTrue(result.isSuccess)
        val desc = result.getOrThrow()
        // TODO (razzle): bad litmus test, make better
        assertEquals(desc.mediaType, MANIFEST_MEDIA_TYPE.toString())
        assertEquals(currentArch, desc.platform!!.architecture)
    }

    @Test
    fun `fetch a layer`() = runTest {
        val repo = registry.repo("dos-games")
        val desc = repo.resolve("1.1.0", ::zarfResolver).getOrThrow()

        val manifest = repo.manifest(desc).getOrThrow()

        val p = repo.pull(
            manifest.config, storage
        )

        assertEquals(
            manifest.config.size.toInt(), p.last()
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
        val manifestDesc = repo.resolve("1.1.0", ::zarfResolver).getOrThrow()
        val layer = repo.manifest(manifestDesc).getOrThrow().layers.maxBy { it.size }

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

    @Test
    fun `pull and remove dos-games`() = runTest {
        val desc = registry.resolve("dos-games", "1.1.0", ::zarfResolver).getOrThrow()
        val prog = registry.pull("dos-games", "1.1.0", ::zarfResolver)

        assertEquals(
            100, prog.last()
        )

        val ref = Reference.parse("127.0.0.1:5005/dos-games:1.1.0").getOrThrow()
        assertEquals(desc.digest, storage.resolve(ref).getOrThrow().digest)

        // TODO: assert that removal of a artifact does not result in removal of any other artifact's dependent layers
        assertTrue(storage.remove(desc).getOrThrow())
        assertFails { storage.resolve(ref).getOrThrow() }
    }

    @Test
    fun `resume-able pulls`() = runTest {
        val cancelAt = listOf(5, 15, 50, -100)

        for (at in cancelAt) {
            launch {
                var lastEmit = 0
                registry.pull("dos-games", "1.1.0", ::zarfResolver).onCompletion { e ->
                    if (at == -100) {
                        assertNull(e)
                        assertEquals(
                            100, lastEmit
                        )
                    } else {
                        assertIs<CancellationException>(e)
                        assertFailsWith<NoSuchElementException> {
                            val desc =
                                runBlocking { registry.resolve("dos-games", "1.1.0", ::zarfResolver).getOrThrow() }
                            storage.resolve {
                                it.digest == desc.digest
                            }.getOrThrow()
                        }
                    }
                }.collect { progress ->
                    lastEmit = progress
                    if (at == progress) cancel()
                }
            }.join()
        }
        val desc = registry.resolve("dos-games", "1.1.0", ::zarfResolver).getOrThrow()
        // TODO: assert that removal of a artifact does not result in removal of any other artifact's dependent layers
        assertTrue(storage.remove(desc).getOrThrow())
    }

    @Test
    fun `concurrent pulls + removals`() {
        assertDoesNotThrow {
            runTest(timeout = kotlin.time.Duration.parse("PT2M")) {
                val p1 = async {
                    registry.pull("dos-games", "1.1.0", ::zarfResolver).collect()
                }
                val p2 = async {
                    registry.pull("library/registry", "latest") { platform ->
                        platform.os == "linux" && platform.architecture == currentArch
                    }.collect()
                }
                awaitAll(p1, p2)
            }
        }

        assertDoesNotThrow {
            runTest {
                val d1 = registry.resolve("dos-games", "1.1.0", ::zarfResolver).getOrThrow()
                val r1 = async {
                    storage.remove(d1).getOrThrow()
                }
                val d2 = registry.resolve("library/registry", "latest") { platform ->
                    platform.os == "linux" && platform.architecture == currentArch
                }.getOrThrow()
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
            mediaType = MANIFEST_CONFIG_MEDIA_TYPE.toString(), stream = dummy
        )

        dummy.reset()

        // config must be pushed before repo is tagged
        repo.push(dummy, dummyDesc).collect()

        val manifest = Manifest(
            schemaVersion = 2, mediaType = MANIFEST_MEDIA_TYPE.toString(), config = dummyDesc, layers = listOf(desc)
        )

        repo.tag(manifest, "latest").also { res ->
            val manifestDesc = res.getOrThrow()
            assertTrue {
                repo.remove(manifestDesc).getOrThrow()
            }
        }
    }

    @Test
    fun `public ecr scopes`() = runTest {
        val ecr = Registry.Builder().client(httpClient).registryURL("https://public.ecr.aws").storage(storage).build()

        val result = ecr.repo("ubuntu/redis").tags()
        assertTrue(result.isSuccess, result.exceptionOrNull().toString())
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
