/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.client.Layout
import com.defenseunicorns.koci.api.client.Registry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Suppress("detekt:All")
fun main() {
  val layout = Layout.create(rootPath = "/Users/landon/projects/koci/testing").getOrNull()!!
  val registry = Registry.create(registryUrl = "https://192.168.3.240:5000")

  runBlocking {
    registry.list().collect {
      it.getOrNull()?.let {
        launch { registry.pull(it.name, it.tags.first(), layout).collect { q -> println(q) } }
      }
    }
  }
}
