/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScopesTest {
    @Test
    fun valid() {
        val testCases = listOf(
            emptyList<String>() to emptyList(),
            listOf("") to listOf(""),
            listOf(ACTION_REGISTRY_CATALOG) to listOf(ACTION_REGISTRY_CATALOG),
            listOf(
                scopeRepository(
                    "ubuntu", listOf(ACTION_PULL, ACTION_PUSH)
                )
            ) to listOf(
                scopeRepository(
                    "ubuntu", listOf(ACTION_PULL, ACTION_PUSH)
                )
            ),
            listOf("repository:foo:push,pull,delete") to listOf("repository:foo:delete,pull,push"),
            listOf("repository:foo:push,pull,push,pull,push,push,pull") to listOf("repository:foo:pull,push"),
            listOf("repository:foo:pull,*,push") to listOf("repository:foo:*"),
            listOf("repository:foo:,") to emptyList(),
            listOf(
                scopeRepository(
                    "ubuntu", listOf(ACTION_PUSH)
                ),
                scopeRepository(
                    "ubuntu", listOf(ACTION_PULL)
                ),
            ) to listOf(
                scopeRepository(
                    "ubuntu", listOf(ACTION_PULL, ACTION_PUSH)
                )
            ),
            listOf("repository:foo:pull", "repository:bar:push") to listOf(
                "repository:bar:push", "repository:foo:pull"
            ),
            listOf(
                "repository:foo:pull",
                "repository:bar:push",
                "repository:foo:push",
                "repository:bar:push,delete,pull",
                "repository:bar:delete,pull",
                "repository:foo:pull",
                "registry:catalog:*",
                "registry:catalog:pull",
            ) to listOf(
                "registry:catalog:*",
                "repository:bar:delete,pull,push",
                "repository:foo:pull,push",
            ),
            listOf(
                "repository:foo:,",
                "repository:bar:,",
            ) to emptyList(),
            listOf("unknown") to listOf("unknown"),
            listOf(
                "repository:foo:pull",
                "unknown",
                "invalid:scope",
                "no:actions:",
                "repository:foo:push",
            ) to listOf(
                "invalid:scope",
                "repository:foo:pull,push",
                "unknown",
            )
        )

        for ((dirty, clean) in testCases) {
            val actual = cleanScopes(dirty)
            assertEquals(clean, actual)
        }
    }

    @Test
    fun invalid() {
        val testCases = listOf(
            listOf("") to ""
        )

        for ((dirty, message) in testCases) {
            assertFails(message) {
                cleanScopes(dirty)
            }
        }
    }
}
