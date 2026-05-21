/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.Koci
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.assertThrows

class KociTest {

  private fun testKoci(root: String = "/oci", fs: FakeFileSystem = FakeFileSystem()): Koci =
    Koci(root = root, fileSystem = fs, dispatcher = Dispatchers.IO)

  @Test
  fun `layout root matches the path given to constructor`() {
    testKoci(root = "/data/oci").use { koci ->
      assertEquals("/data/oci".toPath(), koci.layout.root)
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
  fun `close is idempotent`() {
    val koci = testKoci()
    koci.close()
    koci.close()
  }

  @Test
  fun `close also closes registry clients`() = runTest {
    val koci = testKoci()
    val registry = koci.registry("https://registry.example.com")
    val registry2 = koci.registry("https://registry.example.com")
    koci.close()

    assertThrows<IllegalStateException> { registry.ping() }
    assertThrows<IllegalStateException> { registry2.ping() }
  }
}
