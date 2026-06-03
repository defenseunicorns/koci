/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.TransferEvent
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
  Koci(root = "/tmp/koci-pull-sample").use { koci ->
    val repo = koci.registry("http://localhost:5000").repo("samples/blob")

    repo.pull(tag = "test").collect { event ->
      when (event) {
        is TransferEvent.Progress -> println("progress: ${event.percent}%")
        TransferEvent.Failed -> println("failed (cause logged inside koci)")
      }
    }
  }
}
