/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.samples

import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.config.AuthConfig
import com.defenseunicorns.koci.api.config.TimeoutConfig
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

/**
 * Wires up an authenticated `Registry`. [AuthConfig] is a sealed type with three variants:
 * [AuthConfig.None] (anonymous, the default), [AuthConfig.Basic] (username + password), and
 * [AuthConfig.Bearer] (a pre-acquired token).
 *
 * Auth is registry-scoped — once installed on the shared HTTP client it applies to every
 * `Repository` derived from this `Registry`.
 */
fun main(): Unit = runBlocking {
  Koci(root = "/tmp/koci-auth-sample").use { koci ->
    val basic =
      koci.registry(
        url = "https://registry.example.com",
        auth = AuthConfig.Basic(user = "alice", pass = System.getenv("REGISTRY_PASSWORD") ?: ""),
        timeouts = TimeoutConfig(requestTimeout = 30.seconds),
      )
    println("basic auth ping: ${basic.ping()}")

    val bearer =
      koci.registry(
        url = "https://registry.example.com",
        auth = AuthConfig.Bearer(token = System.getenv("REGISTRY_TOKEN") ?: ""),
      )
    println("bearer auth ping: ${bearer.ping()}")
  }
}
