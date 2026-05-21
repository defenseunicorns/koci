/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.TransferEvent
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
  val payload = "hello from koci".toByteArray()

  Koci(root = "/tmp/koci-push-sample").use { koci ->
    val repo = koci.registry("http://localhost:5000").repo("samples/blob")

    val descriptor =
      Descriptor.fromInputStream(stream = payload.inputStream(), mediaType = "text/plain")

    repo.push(stream = payload.inputStream(), expected = descriptor).collect { event ->
      when (event) {
        is TransferEvent.Progress -> println("uploaded: ${event.percent}%")
        TransferEvent.Failed -> println("push failed")
      }
    }
  }
}
