/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Koci
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
  Koci.create(root = "/tmp/koci-catalog-sample").use { koci ->
    val registry = koci.registry("http://localhost:5000")

    if (!registry.ping()) {
      println("registry not reachable")
      return@runBlocking
    }

    registry.catalog().collect { repos ->
      for (repo in repos) {
        println("${repo.name}: ${repo.tags()}")
      }
    }
  }
}
