/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.PullEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
  val payload = "hello from koci".toByteArray()

  Koci.create(root = "/tmp/koci-push-sample").use { koci ->
    val repo = koci.registry("http://localhost:5000").repo("samples/blob")

    val descriptor =
      Descriptor.fromInputStream(stream = payload.inputStream(), mediaType = "text/plain")

    repo.push(stream = payload.inputStream(), expected = descriptor).collect { event ->
      when (event) {
        is PullEvent.Progress -> println("uploaded: ${event.percent}%")
        PullEvent.Failed -> println("push failed")
      }
    }
  }
}
