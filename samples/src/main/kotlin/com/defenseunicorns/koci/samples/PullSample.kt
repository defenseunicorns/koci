/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.PullEvent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * Pulls an image by tag from an anonymous registry into a local OCI layout and prints
 * percent-complete updates as bytes flow.
 *
 * Demonstrates: constructing [Koci], scoping a `Registry` / `Repository`, and consuming the
 * [PullEvent] flow returned by `Repository.pull(tag)`.
 */
fun main(): Unit = runBlocking {
  Koci(root = "/tmp/koci-pull-sample").use { koci ->
    val repo = koci.registry("https://ghcr.io").repo("linuxcontainers/alpine")

    repo.pull(tag = "latest").collect { event ->
      when (event) {
        is PullEvent.Progress -> println("progress: ${event.percent}%")
        PullEvent.Completed -> println("done")
        PullEvent.Failed -> println("failed (cause logged inside koci)")
      }
    }
  }
}
