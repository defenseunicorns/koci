/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlin.test.Test
import kotlin.test.assertEquals

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
            ) to "localhost:5000/huh")
        )

        for ((tc, want) in testCases) {
            val got = Reference.parse(tc).getOrThrow()
            assertEquals(want.first, got)

            assertEquals(want.second, got.toString())
        }
    }
}
