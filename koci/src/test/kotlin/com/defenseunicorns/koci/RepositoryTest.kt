/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.TestFixtures.buildLayout
import com.defenseunicorns.koci.TestFixtures.digestOf
import com.defenseunicorns.koci.TestFixtures.fakeRepo
import com.defenseunicorns.koci.TestFixtures.testJson
import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Index
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.OciConstants
import com.defenseunicorns.koci.api.Platform
import com.defenseunicorns.koci.api.Reference
import com.defenseunicorns.koci.api.TransferEvent
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem

class RepositoryTest {

  // ── exists ──────────────────────────────────────────────────────────────────

  @Test
  fun `exists returns true when registry confirms blob with 200`() = runTest {
    val blobBytes = "blob".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo = fakeRepo(handler = { respondOk() })
    assertTrue(repo.exists(desc))
  }

  @Test
  fun `exists returns false when registry returns 404`() = runTest {
    val blobBytes = "blob".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo = fakeRepo(handler = { respondError(HttpStatusCode.NotFound) })
    assertFalse(repo.exists(desc))
  }

  @Test
  fun `exists returns false on transport failure`() = runTest {
    val blobBytes = "blob".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo = fakeRepo(handler = { throw IOException("no route") })
    assertFalse(repo.exists(desc))
  }

  // ── tags ────────────────────────────────────────────────────────────────────

  @Test
  fun `tags returns list from successful response`() = runTest {
    val repo =
      fakeRepo(
        handler = {
          respond(
            content = """{"name":"myrepo","tags":["latest","v1.0","v2.0"]}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
      )
    assertEquals(listOf("latest", "v1.0", "v2.0"), repo.tags())
  }

  @Test
  fun `tags returns empty list on failure`() = runTest {
    val repo = fakeRepo(handler = { respondError(HttpStatusCode.InternalServerError) })
    assertEquals(emptyList(), repo.tags())
  }

  // ── resolve ─────────────────────────────────────────────────────────────────

  @Test
  fun `resolve returns descriptor built from response headers`() = runTest {
    val manifestBytes =
      """{"schemaVersion":2,"config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"sha256:${"a".repeat(64)}","size":1},"layers":[]}"""
        .toByteArray()
    val manifestDigest = digestOf(manifestBytes)
    val repo =
      fakeRepo(
        handler = {
          respond(
            content = "",
            status = HttpStatusCode.OK,
            headers =
              headersOf(
                HttpHeaders.ContentType to listOf(OciConstants.MANIFEST_MEDIA_TYPE),
                "Docker-Content-Digest" to listOf(manifestDigest.toString()),
                HttpHeaders.ContentLength to listOf(manifestBytes.size.toString()),
              ),
          )
        }
      )
    val desc = repo.resolve("latest")
    assertNotNull(desc)
    assertEquals(manifestDigest, desc.digest)
    assertEquals(OciConstants.MANIFEST_MEDIA_TYPE, desc.mediaType)
    assertEquals(manifestBytes.size.toLong(), desc.size)
  }

  @Test
  fun `resolve returns when Docker-Content-Digest header is absent`() = runTest {
    val repo =
      fakeRepo(
        handler = {
          respond(
            content = "1234",
            status = HttpStatusCode.OK,
            headers =
              headersOf(
                HttpHeaders.ContentType to listOf(OciConstants.MANIFEST_MEDIA_TYPE),
                HttpHeaders.ContentLength to listOf("100"),
              ),
          )
        }
      )
    assertNotNull(repo.resolve("latest"))
  }

  @Test
  fun `resolve with platform resolver picks matching manifest from index`() = runTest {
    val index =
      Index().apply {
        manifests.add(
          Descriptor(
            mediaType = OciConstants.MANIFEST_MEDIA_TYPE,
            digest = digestOf("amd64-manifest".toByteArray()),
            size = 100,
            platform = Platform(architecture = "amd64", os = "linux"),
          )
        )
        manifests.add(
          Descriptor(
            mediaType = OciConstants.MANIFEST_MEDIA_TYPE,
            digest = digestOf("arm64-manifest".toByteArray()),
            size = 100,
            platform = Platform(architecture = "arm64", os = "linux"),
          )
        )
      }
    val indexJson = testJson.encodeToString(index)
    val repo =
      fakeRepo(
        handler = {
          respond(
            content = indexJson,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, OciConstants.INDEX_MEDIA_TYPE),
          )
        }
      )
    val desc = repo.resolve("latest") { it.architecture == "arm64" }
    assertNotNull(desc)
    assertEquals("arm64", desc.platform?.architecture)
  }

  @Test
  fun `resolve with platform resolver returns null when no platform matches`() = runTest {
    val index =
      Index().apply {
        manifests.add(
          Descriptor(
            mediaType = OciConstants.MANIFEST_MEDIA_TYPE,
            digest = digestOf("amd64-manifest".toByteArray()),
            size = 100,
            platform = Platform(architecture = "amd64", os = "linux"),
          )
        )
      }
    val indexJson = testJson.encodeToString(index)
    val repo =
      fakeRepo(
        handler = {
          respond(
            content = indexJson,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, OciConstants.INDEX_MEDIA_TYPE),
          )
        }
      )
    assertNull(repo.resolve("latest") { it.architecture == "s390x" })
  }

  // ── pull ────────────────────────────────────────────────────────────────────

  @Test
  fun `pull emits Failed when tag cannot be resolved`() = runTest {
    val repo = fakeRepo(handler = { respondError(HttpStatusCode.NotFound) })
    val events = repo.pull("missing").toList()
    assertTrue(events.contains(TransferEvent.Failed))
  }

  @Test
  @Suppress("detekt:LongMethod")
  fun `pull completes with Progress 100 for a simple single layer manifest`() = runTest {
    val fs = FakeFileSystem()
    val store = buildLayout(fs)

    val configBytes = "{}".toByteArray()
    val layerBytes = "layer content".toByteArray()
    val configDesc =
      Descriptor(
        mediaType = "application/vnd.oci.image.config.v1+json",
        digest = digestOf(configBytes),
        size = configBytes.size.toLong(),
      )
    val layerDesc =
      Descriptor(
        mediaType = "application/vnd.oci.image.layer.v1.tar+gzip",
        digest = digestOf(layerBytes),
        size = layerBytes.size.toLong(),
      )
    val manifest = Manifest(config = configDesc, layers = listOf(layerDesc))
    val manifestBytes = testJson.encodeToString(manifest).toByteArray()
    val manifestDigest = digestOf(manifestBytes)

    val repo =
      fakeRepo(
        name = "myrepo",
        store = store,
        handler = { req ->
          val path = req.url.encodedPath
          val method = req.method
          when {
            method == HttpMethod.Head && path.contains("/manifests/latest") ->
              respond(
                content = "",
                status = HttpStatusCode.OK,
                headers =
                  headersOf(
                    HttpHeaders.ContentType to listOf(OciConstants.MANIFEST_MEDIA_TYPE),
                    "Docker-Content-Digest" to listOf(manifestDigest.toString()),
                    HttpHeaders.ContentLength to listOf(manifestBytes.size.toString()),
                  ),
              )

            method == HttpMethod.Get && path.contains("/manifests/") ->
              respond(
                content = manifestBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, OciConstants.MANIFEST_MEDIA_TYPE),
              )

            method == HttpMethod.Get && path.contains("/blobs/${configDesc.digest}") ->
              respond(
                content = configBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
              )

            method == HttpMethod.Get && path.contains("/blobs/${layerDesc.digest}") ->
              respond(
                content = layerBytes,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
              )

            else -> respondError(HttpStatusCode.NotFound)
          }
        },
      )

    val events = repo.pull("latest").toList()
    assertEquals(TransferEvent.Progress(100), events.last())

    val ref = Reference("registry.example.com", "myrepo", "latest")
    assertNotNull(store.resolveReference(ref))
  }

  // ── fetch ───────────────────────────────────────────────────────────────────

  @Test
  fun `fetch delivers blob bytes to handler and returns handler result`() = runTest {
    val content = "raw bytes"
    val blobBytes = content.toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo =
      fakeRepo(
        handler = {
          respond(
            content = blobBytes,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
          )
        }
      )
    val result = repo.fetch(desc) { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    assertEquals(content, result)
  }

  @Test
  fun `fetch returns null when remote returns 404`() = runTest {
    val blobBytes = "data".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo = fakeRepo(handler = { respondError(HttpStatusCode.NotFound) })
    assertNull(repo.fetch(desc) { it.readBytes() })
  }

  // ── push(stream, descriptor) ─────────────────────────────────────────────────

  @Test
  fun `push stream skips upload and succeeds when blob already exists on remote`() = runTest {
    val blobBytes = "existing blob".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    var postCalled = false
    val repo =
      fakeRepo(
        handler = { req ->
          when (req.method) {
            HttpMethod.Head -> respondOk()
            HttpMethod.Post -> {
              postCalled = true
              respondError(HttpStatusCode.InternalServerError)
            }
            else -> respondError(HttpStatusCode.NotFound)
          }
        }
      )
    val events = repo.push(blobBytes.inputStream(), desc).toList()
    assertEquals(TransferEvent.Progress(100), events.last())
    assertFalse(postCalled)
  }

  @Test
  fun `push stream completes via monolithic upload`() = runTest {
    val blobBytes = "new blob content".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo =
      fakeRepo(
        handler = { req ->
          when (req.method) {
            HttpMethod.Head -> respondError(HttpStatusCode.NotFound)
            HttpMethod.Post ->
              respond(
                content = "",
                status = HttpStatusCode.Accepted,
                headers =
                  headersOf(
                    HttpHeaders.Location to listOf("/v2/myrepo/blobs/uploads/session1"),
                    HttpHeaders.Range to listOf("0-0"),
                  ),
              )
            HttpMethod.Put -> respond(content = "", status = HttpStatusCode.Created)
            else -> respondError(HttpStatusCode.NotFound)
          }
        }
      )
    val events = repo.push(blobBytes.inputStream(), desc).toList()
    assertEquals(TransferEvent.Progress(100), events.last())
  }

  @Test
  fun `push stream emits Failed when upload session cannot be started`() = runTest {
    val blobBytes = "fresh blob".toByteArray()
    val desc =
      Descriptor(
        mediaType = "application/octet-stream",
        digest = digestOf(blobBytes),
        size = blobBytes.size.toLong(),
      )
    val repo =
      fakeRepo(
        handler = { req ->
          when (req.method) {
            HttpMethod.Head -> respondError(HttpStatusCode.NotFound)
            HttpMethod.Post -> respondError(HttpStatusCode.InternalServerError)
            else -> respondError(HttpStatusCode.NotFound)
          }
        }
      )
    val events = repo.push(blobBytes.inputStream(), desc).toList()
    assertTrue(events.contains(TransferEvent.Failed))
  }

  // ── tag ─────────────────────────────────────────────────────────────────────

  @Test
  fun `tag manifest returns descriptor with MANIFEST media type on 201`() = runTest {
    val configDesc =
      Descriptor(
        mediaType = "application/vnd.oci.image.config.v1+json",
        digest = digestOf("cfg".toByteArray()),
        size = 3,
      )
    val manifest = Manifest(config = configDesc, layers = emptyList())
    val repo = fakeRepo(handler = { respond(content = "", status = HttpStatusCode.Created) })
    val result = repo.tag(manifest, "v1.0")
    assertNotNull(result)
    assertEquals(OciConstants.MANIFEST_MEDIA_TYPE, result.mediaType)
    assertNotNull(result.digest)
  }

  @Test
  fun `tag manifest returns null when registry returns non-201`() = runTest {
    val configDesc =
      Descriptor(
        mediaType = "application/vnd.oci.image.config.v1+json",
        digest = digestOf("cfg".toByteArray()),
        size = 3,
      )
    val manifest = Manifest(config = configDesc, layers = emptyList())
    val repo = fakeRepo(handler = { respondError(HttpStatusCode.InternalServerError) })
    assertNull(repo.tag(manifest, "v1.0"))
  }

  @Test
  fun `tag manifest returns null for an invalid tag`() = runTest {
    val configDesc =
      Descriptor(
        mediaType = "application/vnd.oci.image.config.v1+json",
        digest = digestOf("cfg".toByteArray()),
        size = 3,
      )
    val manifest = Manifest(config = configDesc, layers = emptyList())
    var putCalled = false
    val repo =
      fakeRepo(
        handler = { req ->
          when (req.method) {
            HttpMethod.Put -> {
              putCalled = true
              respond(content = "", status = HttpStatusCode.Created)
            }
            else -> respondError(HttpStatusCode.NotFound)
          }
        }
      )
    assertNull(repo.tag(manifest, "bad tag with spaces"))
    assertFalse(putCalled)
  }

  @Test
  fun `tag index returns descriptor with INDEX media type on 201`() = runTest {
    val index = Index()
    val repo = fakeRepo(handler = { respond(content = "", status = HttpStatusCode.Created) })
    val result = repo.tag(index, "latest")
    assertNotNull(result)
    assertEquals(OciConstants.INDEX_MEDIA_TYPE, result.mediaType)
    assertNotNull(result.digest)
  }
}
