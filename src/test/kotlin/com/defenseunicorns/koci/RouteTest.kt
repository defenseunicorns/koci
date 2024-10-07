/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("detekt:MaxLineLength")
class RouteTest {
    private val base = URLBuilder(host = "127.0.0.1", port = 5000).build().toString()
    private val router = Router(base)

    @Test
    fun `root has v2 API prefix`() {
        assertEquals("http://127.0.0.1:5000/v2/", router.base().toString())
    }

    @Test
    fun `registry catalog`() {
        assertEquals("http://127.0.0.1:5000/v2/_catalog", router.catalog().toString())
    }

    @Test
    fun `registry catalog pagination`() {
        assertEquals("http://127.0.0.1:5000/v2/_catalog?n=3", router.catalog(3).toString())
        assertEquals(
            "http://127.0.0.1:5000/v2/_catalog?n=3&last=core",
            router.catalog(3, "core").toString()
        )
    }

    @Test
    fun `repo tags`() {
        assertEquals(
            "http://127.0.0.1:5000/v2/library/registry/tags/list",
            router.tags("library/registry").toString()
        )
    }

    @Test
    fun `manifest refs`() {
        assertEquals(
            "http://127.0.0.1:5000/v2/library/registry/manifests/2.8.3",
            router.manifest("library/registry", "2.8.3").toString()
        )

        assertEquals(
            "http://127.0.0.1:5000/v2/library/registry/manifests/sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d",
            router.manifest(
                "library/registry",
                "sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d"
            ).toString()
        )

        assertEquals(
            "http://127.0.0.1:5000/v2/library/registry/manifests/sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d",
            router.manifest(
                "library/registry",
                Descriptor(
                    mediaType = MANIFEST_MEDIA_TYPE.toString(),
                    digest = Digest("sha256:12120425f07de11a1b899e418d4b0ea174c8d4d572d45bdb640f93bc7ca06a3d"),
                    size = 0 // dummy size
                )
            ).toString()
        )
    }

    @Test
    fun blobs() {
        val desc = Descriptor(
            mediaType = "application/vnd.zarf.layer.v1.blob",
            digest = Digest("sha256:a658f2ea6b48ffbd284dc14d82f412a89f30851d0fb7ad01c86f245f0a5ab149"),
            size = 911,
            annotations = mutableMapOf(ANNOTATION_TITLE to "zarf.yaml")
        )

        assertEquals(
            "http://127.0.0.1:5000/v2/dos-games/blobs/${desc.digest}",
            router.blob(
                "dos-games", desc
            ).toString()
        )
    }
}
