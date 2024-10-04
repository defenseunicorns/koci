package com.defenseunicorns.koci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ScopesTest {
    @Test
    fun valid() {
        val testCases = listOf(
            listOf("") to listOf(""),
            listOf(ACTION_REGISTRY_CATALOG) to listOf(ACTION_REGISTRY_CATALOG),
            listOf(
                scopeRepository(
                    "ubuntu",
                    listOf(ACTION_PULL, ACTION_PUSH)
                )
            ) to listOf(
                scopeRepository(
                    "ubuntu",
                    listOf(ACTION_PULL, ACTION_PUSH)
                )
            ),
            listOf(
                scopeRepository(
                    "ubuntu",
                    listOf(ACTION_PULL)
                ),
                scopeRepository(
                    "ubuntu",
                    listOf(ACTION_PUSH)
                )
            ) to listOf(
                scopeRepository(
                    "ubuntu",
                    listOf(ACTION_PULL, ACTION_PUSH)
                )
            )
        )

        for ((dirty, clean) in testCases) {
            val actual = cleanScopes(dirty)
            assertEquals(clean, actual)
            println(actual)
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
