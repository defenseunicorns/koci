/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.client.Layout
import com.defenseunicorns.koci.api.client.Registry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking

@Suppress("detekt:All")
fun main() {
  val layout = Layout.create(rootPath = "/Users/landon/projects/koci/testing")!!
  val registry = Registry.create(registryUrl = "https://localhost:5000")

  runBlocking {
    registry.list().collect {
      println(it?.name)
      it?.let {
        registry
          .pull(registry.repo(it.name), it.tags.last(), layout)
          .distinctUntilChanged()
          .collect { progress -> println("$progress%") }
      }
    }
  }
}
