/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import com.defenseunicorns.koci.api.Registry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP timeouts applied to every request made against a [Registry]. Shared by pulls and pushes —
 * request latency is a connection concern, not a direction concern.
 *
 * Defaults to 10 seconds, matching OkHttp's defaults.
 */
public class TimeoutConfig(
  /** End-to-end deadline for a single request (from send to full response received). */
  public val requestTimeout: Duration = 10.seconds,

  /** Maximum time allowed to establish a TCP/TLS connection. */
  public val connectTimeout: Duration = 10.seconds,

  /** Maximum idle time between socket reads/writes during an in-flight request. */
  public val socketTimeout: Duration = 10.seconds,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as TimeoutConfig

    if (requestTimeout != other.requestTimeout) return false
    if (connectTimeout != other.connectTimeout) return false
    if (socketTimeout != other.socketTimeout) return false

    return true
  }

  override fun hashCode(): Int {
    var result = requestTimeout.hashCode()
    result = 31 * result + connectTimeout.hashCode()
    result = 31 * result + socketTimeout.hashCode()
    return result
  }

  override fun toString(): String =
    "TimeoutConfig(requestTimeout=$requestTimeout, connectTimeout=$connectTimeout, " +
      "socketTimeout=$socketTimeout)"
}
