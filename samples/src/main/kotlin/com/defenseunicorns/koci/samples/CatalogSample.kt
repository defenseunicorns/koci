/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Koci
import kotlinx.coroutines.runBlocking

/**
 * Walks a registry: pings it, lists every repository it advertises, and prints each repository's
 * tag list.
 *
 * `Registry.catalog()` returns the full list in one shot; `Registry.catalog(n)` returns a `Flow` of
 * pages if you'd rather stream. `Repository.tags()` does the same one-shot listing per repo.
 */
fun main(): Unit = runBlocking {
  Koci(root = "/tmp/koci-catalog-sample").use { koci ->
    val registry = koci.registry("http://localhost:5000")

    if (!registry.ping()) {
      println("registry not reachable")
      return@runBlocking
    }

    for (repo in registry.catalog()) {
      println("${repo.name}: ${repo.tags()}")
    }
  }
}
