///*
// * Copyright 2024-2025 Defense Unicorns
// * SPDX-License-Identifier: Apache-2.0
// */
//
//package com.defenseunicorns.koci
//
//import com.defenseunicorns.koci.http.Router
//import com.defenseunicorns.koci.models.ANNOTATION_TITLE
//import com.defenseunicorns.koci.models.Descriptor
//import com.defenseunicorns.koci.models.MANIFEST_MEDIA_TYPE
//import com.defenseunicorns.koci.models.content.Digest
//import io.ktor.http.URLBuilder
//import kotlin.test.Test
//import kotlin.test.assertEquals
//
//@Suppress("detekt:MaxLineLength")
//class RouteTest {
//  private val base = URLBuilder(host = "127.0.0.1", port = 5000).build().toString()
//  private val router = Router(base)
//
//  @Test
//  fun `root has v2 API prefix`() {
//    assertEquals("http://127.0.0.1:5000/v2/", router.base().toString())
//  }
//
//  @Test
//  fun `registry catalog`() {
//    assertEquals("http://127.0.0.1:5000/v2/_catalog", router.catalog().toString())
//  }
//
//  @Test
//  fun `registry catalog pagination`() {
//    assertEquals("http://127.0.0.1:5000/v2/_catalog?n=3", router.catalog(3).toString())
//    assertEquals(
//      "http://127.0.0.1:5000/v2/_catalog?n=3&last=core",
//      router.catalog(3, "core").toString(),
//    )
//  }
//
//  @Test
//  fun `repo tags`() {
//    assertEquals(
//      "http://127.0.0.1:5000/v2/library/registry/tags/list",
//      router.tags("library/registry").toString(),
//    )
//  }
//
//  @Test
//  fun `manifest refs`() {
//    assertEquals(
//      "http://127.0.0.1:5000/v2/library/registry/manifests/2.8.3",
//      router.manifest("library/registry", "2.8.3").toString(),
//    )
//
//    assertEquals(
//      "http://127.0.0.1:5000/v2/library/registry/manifests/sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d",
//      router
//        .manifest(
//          "library/registry",
//          "sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d",
//        )
//        .toString(),
//    )
//
//    assertEquals(
//      "http://127.0.0.1:5000/v2/library/registry/manifests/sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d",
//      router
//        .manifest(
//          "library/registry",
//          Descriptor(
//            mediaType = MANIFEST_MEDIA_TYPE.toString(),
//            digest =
//              Digest("sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d"),
//            size = 0, // dummy size
//          ),
//        )
//        .toString(),
//    )
//  }
//
//  @Test
//  fun blobs() {
//    val desc =
//      Descriptor(
//        mediaType = "application/vnd.zarf.layer.v1.blob",
//        digest = Digest("sha256:a658f2ea6b48ffbd284dc14d82f412a89f30851d0fb7ad01c86f245f0a5ab149"),
//        size = 911,
//        annotations = mutableMapOf(ANNOTATION_TITLE to "zarf.yaml"),
//      )
//
//    assertEquals(
//      "http://127.0.0.1:5000/v2/dos-games/blobs/${desc.digest}",
//      router.blob("dos-games", desc).toString(),
//    )
//  }
//
//  @Test
//  fun uploads() {
//    assertEquals("http://127.0.0.1:5000/v2/foo/blobs/uploads/", router.uploads("foo").toString())
//  }
//
//  @Test
//  fun `parse upload location`() {
//    val abs =
//      "http://127.0.0.1:5000/v2/test-upload/blobs/uploads/4e67c002-da9b-432e-a410-79b403bfcd87?_state=DGT2TjfmtQaET0K3qfXpO_4am5scH987hejkECPV2Ep7Ik5hbWUiOiJ0ZXN0LXVwbG9hZCIsIlVVSUQiOiI0ZTY3YzAwMi1kYTliLTQzMmUtYTQxMC03OWI0MDNiZmNkODciLCJPZmZzZXQiOjE1NzI4NjQwLCJTdGFydGVkQXQiOiIyMDI1LTAyLTIxVDE4OjQwOjMyWiJ9"
//    val rel =
//      "/v2/test-upload/blobs/uploads/4e67c002-da9b-432e-a410-79b403bfcd87?_state=DGT2TjfmtQaET0K3qfXpO_4am5scH987hejkECPV2Ep7Ik5hbWUiOiJ0ZXN0LXVwbG9hZCIsIlVVSUQiOiI0ZTY3YzAwMi1kYTliLTQzMmUtYTQxMC03OWI0MDNiZmNkODciLCJPZmZzZXQiOjE1NzI4NjQwLCJTdGFydGVkQXQiOiIyMDI1LTAyLTIxVDE4OjQwOjMyWiJ9"
//    assertEquals(abs, router.parseUploadLocation(abs).toString())
//    assertEquals(abs, router.parseUploadLocation(rel).toString())
//  }
//
//  @Test
//  fun `blob mount`() {
//    val desc =
//      Descriptor(
//        mediaType = "application/vnd.oci.image.layer.v1.tar+gzip",
//        digest = Digest("sha256:a658f2ea6b48ffbd284dc14d82f412a89f30851d0fb7ad01c86f245f0a5ab149"),
//        size = 1024,
//        annotations = mutableMapOf(),
//      )
//
//    val targetRepo = "target-repo"
//    val sourceRepo = "source-repo"
//
//    val expectedUrl =
//      "http://127.0.0.1:5000/v2/target-repo/blobs/uploads/?mount=sha256%3Aa658f2ea6b48ffbd284dc14d82f412a89f30851d0fb7ad01c86f245f0a5ab149&from=source-repo"
//
//    val actualUrl = router.blobMount(targetRepo, sourceRepo, desc).toString()
//
//    assertEquals(
//      expectedUrl,
//      actualUrl,
//      "The Url for blob mounting should be constructed correctly",
//    )
//  }
//}
