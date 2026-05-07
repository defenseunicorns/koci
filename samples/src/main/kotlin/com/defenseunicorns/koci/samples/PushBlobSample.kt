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

/**
 * Pushes an arbitrary blob to a local registry. The [Descriptor] describes the blob being pushed
 * (size + sha256 digest); `Descriptor.fromInputStream` computes both by streaming the bytes through
 * a hashing source.
 *
 * The local docker-compose registry on `:5000` is a convenient target. Authenticated registries
 * take an `AuthConfig` — see `AuthSample.kt`.
 */
fun main(): Unit = runBlocking {
  val payload = "hello from koci".toByteArray()

  Koci(root = "/tmp/koci-push-sample").use { koci ->
    val repo = koci.registry("http://localhost:5000").repo("samples/blob")

    val descriptor =
      Descriptor.fromInputStream(stream = payload.inputStream(), mediaType = "text/plain")
        ?: error("could not compute descriptor")

    repo.push(stream = payload.inputStream(), expected = descriptor).collect { event ->
      when (event) {
        is PullEvent.Progress -> println("uploaded: ${event.percent} bytes")
        PullEvent.Completed -> println("pushed ${descriptor.digest}")
        PullEvent.Failed -> println("push failed")
      }
    }
  }
}
