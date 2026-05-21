/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.Koci
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class KociTest {

  private fun testKoci(root: String = "/oci", fs: FakeFileSystem = FakeFileSystem()): Koci {
    val client = HttpClient(MockEngine) { engine { addHandler { respondOk() } } }
    return Koci.create(
      root = root,
      fileSystem = fs,
      dispatcher = Dispatchers.IO,
      httpClient = client,
      logger = TestFixtures.NoOpLogger,
    )
  }

  @Test
  fun `layout root matches the path given to constructor`() {
    testKoci(root = "/data/oci").use { koci ->
      assertEquals("/data/oci".toPath(), koci.layout.root)
    }
  }

  @Test
  fun `oci layout directory structure is created on construction`() {
    val fs = FakeFileSystem()
    testKoci(root = "/store", fs = fs).use {
      assertTrue(fs.exists("/store/oci-layout".toPath()))
      assertTrue(fs.exists("/store/blobs/sha256".toPath()))
      assertTrue(fs.exists("/store/blobs/sha512".toPath()))
    }
  }

  @Test
  fun `registry returns a registry bound to the given url`() {
    testKoci().use { koci ->
      val registry = koci.registry("https://ghcr.io")
      assertEquals("https://ghcr.io", registry.url)
    }
  }

  @Test
  fun `registry name is the host portion of the url`() {
    testKoci().use { koci ->
      assertEquals("ghcr.io", koci.registry("https://ghcr.io").name)
      assertEquals("registry.example.com", koci.registry("https://registry.example.com:443").name)
    }
  }

  @Test
  fun `close is idempotent`() {
    val koci = testKoci()
    koci.close()
    koci.close()
  }

  @Test
  fun `toString includes the root path`() {
    testKoci(root = "/my/store").use { koci -> assertTrue(koci.toString().contains("/my/store")) }
  }

  @Test
  fun `koci can be used with use block`() {
    var layoutRootSeen = false
    Koci.create(
        root = "/tmp/oci",
        fileSystem = FakeFileSystem(),
        dispatcher = Dispatchers.IO,
        httpClient = HttpClient(MockEngine) { engine { addHandler { respondOk() } } },
        logger = TestFixtures.NoOpLogger,
      )
      .use { koci -> layoutRootSeen = koci.layout.root.toString().isNotEmpty() }
    assertTrue(layoutRootSeen)
  }
}
