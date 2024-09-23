/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DigestTest {
    @Test
    fun simple() {
        assertFailsWith<IllegalArgumentException> {
            Digest("")
        }
        assertFailsWith<IllegalArgumentException> {
            Digest("s:")
        }
        assertFailsWith<IllegalArgumentException> {
            Digest("s:5")
        }
        assertFailsWith<IllegalArgumentException> {
            Digest("sha256:5")
        }

        val good = Digest("sha256:a658f2ea6b48ffbd284dc14d82f412a89f30851d0fb7ad01c86f245f0a5ab149")
        assertEquals(RegisteredAlgorithm.SHA256, good.algorithm)
        assertEquals("sha256", good.algorithm.toString())
        assertEquals("a658f2ea6b48ffbd284dc14d82f412a89f30851d0fb7ad01c86f245f0a5ab149", good.hex)
    }
}

class ReferenceTest {
    @Test
    fun simple() {
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