/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.TestFixtures.buildLayout
import com.defenseunicorns.koci.TestFixtures.testJson
import com.defenseunicorns.koci.TestFixtures.writeBlob
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.OciConstants
import com.defenseunicorns.koci.api.Platform
import com.defenseunicorns.koci.api.Reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem

class LayoutTest {

  @Test
  fun `catalog returns empty list when layout has no tagged manifests`() {
    val layout = buildLayout()
    assertEquals(emptyList(), layout.catalog())
  }

  @Test
  fun `catalog reflects all tagged descriptors`() = runTest {
    val layout = buildLayout()
    repeat(3) { i ->
      val bytes = "content-$i".toByteArray()
      val desc = layout.writeBlob(bytes, OciConstants.MANIFEST_MEDIA_TYPE)
      val ref = Reference("registry.example.com", "repo", "tag-$i")
      layout.tag(desc, ref)
    }
    assertEquals(3, layout.catalog().size)
  }

  @Test
  fun `catalog does not include untagged blobs`() = runTest {
    val layout = buildLayout()
    layout.writeBlob("untagged".toByteArray())
    assertEquals(emptyList(), layout.catalog())
  }

  @Test
  fun `fetchBlob returns null for absent descriptor`() = runTest {
    val layout = buildLayout()
    val bytes = "absent".toByteArray()
    val desc = layout.writeBlob(bytes)
    layout.remove(desc)
    assertNull(layout.fetchBlob(desc) { it.readUtf8() })
  }

  @Test
  fun `fetchBlob delivers blob bytes to handler`() = runTest {
    val layout = buildLayout()
    val content = "hello layout"
    val desc = layout.writeBlob(content.toByteArray())
    val result = layout.fetchBlob(desc) { it.readUtf8() }
    assertEquals(content, result)
  }

  @Test
  fun `fetchBlob returns value produced by handler`() = runTest {
    val layout = buildLayout()
    val desc = layout.writeBlob("data".toByteArray())
    val result = layout.fetchBlob(desc) { 42 }
    assertEquals(42, result)
  }

  @Test
  fun `resolveDescriptor returns null when predicate never matches`() = runTest {
    val layout = buildLayout()
    val desc = layout.writeBlob("x".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    layout.tag(desc, Reference("r.example.com", "repo", "v1"))
    assertNull(layout.resolveDescriptor { it.mediaType == "no/match" })
  }

  @Test
  fun `resolveDescriptor returns first descriptor satisfying predicate`() = runTest {
    val layout = buildLayout()
    val descA = layout.writeBlob("a".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    val descB = layout.writeBlob("b".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    layout.tag(descA, Reference("r.example.com", "repo", "tag-a"))
    layout.tag(descB, Reference("r.example.com", "repo", "tag-b"))
    val found = layout.resolveDescriptor { it.digest == descA.digest }
    assertNotNull(found)
    assertEquals(descA.digest, found.digest)
  }

  @Test
  fun `resolveReference returns null for unknown reference`() = runTest {
    val layout = buildLayout()
    val ref = Reference("r.example.com", "repo", "missing")
    assertNull(layout.resolveReference(ref))
  }

  @Test
  fun `resolveReference returns descriptor for its tagged reference`() = runTest {
    val layout = buildLayout()
    val desc = layout.writeBlob("manifest".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    val ref = Reference("r.example.com", "repo", "latest")
    layout.tag(desc, ref)
    val resolved = layout.resolveReference(ref)
    assertNotNull(resolved)
    assertEquals(desc.digest, resolved.digest)
  }

  @Test
  fun `resolveReference with platform resolver picks matching platform`() = runTest {
    val layout = buildLayout()
    val amd64Bytes = "amd64-manifest".toByteArray()
    val arm64Bytes = "arm64-manifest".toByteArray()
    val amd64 =
      layout
        .writeBlob(amd64Bytes, OciConstants.MANIFEST_MEDIA_TYPE)
        .copy(platform = Platform(architecture = "amd64", os = "linux"))
    val arm64 =
      layout
        .writeBlob(arm64Bytes, OciConstants.MANIFEST_MEDIA_TYPE)
        .copy(platform = Platform(architecture = "arm64", os = "linux"))
    val ref = Reference("r.example.com", "repo", "latest")
    layout.tag(amd64, ref)
    layout.tag(arm64, ref)

    val resolved = layout.resolveReference(ref) { it.architecture == "arm64" }
    assertNotNull(resolved)
    assertEquals("arm64", resolved.platform?.architecture)
  }

  @Test
  fun `tag makes descriptor findable by reference`() = runTest {
    val layout = buildLayout()
    val desc = layout.writeBlob("m".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    val ref = Reference("r.example.com", "repo", "v2")
    layout.tag(desc, ref)
    assertNotNull(layout.resolveReference(ref))
  }

  @Test
  fun `tag replaces existing entry for the same reference`() = runTest {
    val layout = buildLayout()
    val ref = Reference("r.example.com", "repo", "stable")
    val old = layout.writeBlob("old".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    val new = layout.writeBlob("new".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    layout.tag(old, ref)
    layout.tag(new, ref)
    assertEquals(1, layout.catalog().size)
    assertEquals(new.digest, layout.resolveReference(ref)?.digest)
  }

  @Test
  fun `tag with distinct references coexist in catalog`() = runTest {
    val layout = buildLayout()
    val desc = layout.writeBlob("shared".toByteArray(), OciConstants.MANIFEST_MEDIA_TYPE)
    layout.tag(desc, Reference("r.example.com", "repo", "v1"))
    layout.tag(desc, Reference("r.example.com", "repo", "v2"))
    assertEquals(2, layout.catalog().size)
  }

  @Test
  fun `remove by reference returns true when reference is absent`() = runTest {
    val layout = buildLayout()
    val ref = Reference("r.example.com", "repo", "ghost")
    assertTrue(layout.remove(ref))
    assertEquals(emptyList(), layout.catalog())
  }

  @Test
  fun `remove by reference clears descriptor from catalog`() = runTest {
    val layout = buildLayout()
    val configDesc =
      layout.writeBlob("{}".toByteArray(), "application/vnd.oci.image.config.v1+json")
    val manifestBytes =
      testJson.encodeToString(Manifest(config = configDesc, layers = emptyList())).toByteArray()
    val manifestDesc = layout.writeBlob(manifestBytes, OciConstants.MANIFEST_MEDIA_TYPE)
    val ref = Reference("r.example.com", "repo", "v1")
    layout.tag(manifestDesc, ref)
    layout.remove(ref)
    assertEquals(emptyList(), layout.catalog())
  }

  @Test
  fun `remove manifest preserves shared layer still referenced by another manifest`() = runTest {
    val fs = FakeFileSystem()
    val layout = buildLayout(fs)

    val layerBytes = "shared layer".toByteArray()
    val layerDesc = layout.writeBlob(layerBytes, "application/vnd.oci.image.layer.v1.tar+gzip")
    val configADesc =
      layout.writeBlob("config-a".toByteArray(), "application/vnd.oci.image.config.v1+json")
    val configBDesc =
      layout.writeBlob("config-b".toByteArray(), "application/vnd.oci.image.config.v1+json")

    val manifestABytes =
      testJson
        .encodeToString(Manifest(config = configADesc, layers = listOf(layerDesc)))
        .toByteArray()
    val manifestBBytes =
      testJson
        .encodeToString(Manifest(config = configBDesc, layers = listOf(layerDesc)))
        .toByteArray()
    val manifestADesc = layout.writeBlob(manifestABytes, OciConstants.MANIFEST_MEDIA_TYPE)
    val manifestBDesc = layout.writeBlob(manifestBBytes, OciConstants.MANIFEST_MEDIA_TYPE)

    layout.tag(manifestADesc, Reference("r.example.com", "repo", "tag-a"))
    layout.tag(manifestBDesc, Reference("r.example.com", "repo", "tag-b"))

    assertTrue(layout.remove(manifestADesc))

    assertNotNull(layout.fetchBlob(layerDesc) { it.readUtf8() })
    assertEquals(1, layout.catalog().size)
  }

  @Test
  fun `gc returns empty list when layout has no blobs on disk`() = runTest {
    val layout = buildLayout()
    assertEquals(emptyList(), layout.gc())
  }

  @Test
  fun `gc deletes orphan blobs and returns their digests`() = runTest {
    val layout = buildLayout()
    val orphanBytes = "orphan data".toByteArray()
    val orphanDesc = layout.writeBlob(orphanBytes)
    val deleted = layout.gc()
    assertTrue(orphanDesc.digest in deleted)
    assertNull(layout.fetchBlob(orphanDesc) { it.readUtf8() })
  }

  @Test
  fun `gc preserves blobs referenced by a tagged manifest`() = runTest {
    val layout = buildLayout()
    val configBytes = "cfg".toByteArray()
    val layerBytes = "lyr".toByteArray()
    val configDesc = layout.writeBlob(configBytes, "application/vnd.oci.image.config.v1+json")
    val layerDesc = layout.writeBlob(layerBytes, "application/vnd.oci.image.layer.v1.tar+gzip")
    val manifestBytes =
      testJson
        .encodeToString(Manifest(config = configDesc, layers = listOf(layerDesc)))
        .toByteArray()
    val manifestDesc = layout.writeBlob(manifestBytes, OciConstants.MANIFEST_MEDIA_TYPE)
    layout.tag(manifestDesc, Reference("r.example.com", "repo", "v1"))

    assertEquals(emptyList(), layout.gc())
    assertNotNull(layout.fetchBlob(layerDesc) { it.readUtf8() })
    assertNotNull(layout.fetchBlob(configDesc) { it.readUtf8() })
  }
}
