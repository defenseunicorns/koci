/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferenceTest {
    @Test
    @Suppress("detekt:MaxLineLength")
    fun table() {
        val testCases = mapOf(
            // valid form A
            "localhost:5000/library/registry@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20" to (
                    Reference(
                        "localhost:5000",
                        "library/registry",
                        "sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20"
                    ) to "localhost:5000/library/registry@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20"),

            // valid form B
            "localhost:5000/library/registry:2.8.3@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20" to (
                    Reference(
                        "localhost:5000",
                        "library/registry",
                        "sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20"
                        // note that the tag is lost upon parsing as a digest is a more specific reference
                    ) to "localhost:5000/library/registry@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20"),

            // valid form C
            "localhost:5000/library/registry:2.8.3" to (Reference(
                "localhost:5000", "library/registry", "2.8.3"
            ) to "localhost:5000/library/registry:2.8.3"),

            // valid form D
            "localhost:5000/huh" to (Reference(
                "localhost:5000", "huh", ""
            ) to "localhost:5000/huh"),

            "test:5000/repo:tag" to (Reference(
                "test:5000", "repo", "tag"
            ) to "test:5000/repo:tag")
        )

        for ((tc, want) in testCases) {
            val got = Reference.parse(tc).getOrThrow()
            assertEquals(want.first, got)

            assertEquals(want.second, got.toString())
        }

        data class Invalid(
            val string: String,
            val reference: Reference,
            val message: String,
        )

        // mirrored from https://github.com/containers/image/blob/main/docker/reference/reference_test.go
        val invalidTestCases = listOf(
            Invalid(
                "", Reference(
                    "", "", ""
                ), "registry cannot be empty"
            ),
            Invalid(
                ":justtag", Reference(
                    "", "", "justtag"
                ), "registry cannot be empty"
            ),
            Invalid(
                "@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                Reference(
                    "", "", "@sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                ), "registry cannot be empty"
            ),
            Invalid(
                "docker.io/validname@invaliddigest:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                Reference(
                    "docker.io", "validname", "invaliddigest:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                ),
                "invaliddigest is not one of the registered algorithms"
            ),
            Invalid(
                "docker.io/validname@sha256:fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                Reference(
                    "docker.io", "validname", "sha256:fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                ),
                "sha256 algorithm specified but hex length is not 64"
            ),
            Invalid(
                "docker.io/validname@sha512:fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                Reference(
                    "docker.io", "validname", "sha512:fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                ),
                "sha512 algorithm specified but hex length is not 128"
            )
        )

        for (tc in invalidTestCases) {
            assertFailsWith<IllegalArgumentException> { Reference.parse(tc.string).getOrThrow() }.also {
                assertEquals(tc.message, it.message)
            }
            assertFailsWith<IllegalArgumentException> { tc.reference.validate() }.also {
                assertEquals(tc.message, it.message)
            }
        }
    }
}
