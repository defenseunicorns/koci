/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import com.defenseunicorns.koci.api.Registry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP timeouts applied to every request against a [Registry]. [requestTimeout] is the absolute
 * deadline for an entire request, while [socketTimeout] catches stalled transfers by tripping only
 * when the socket goes idle. The default leaves [requestTimeout] uncapped so large blob transfers
 * aren't killed mid-flight.
 */
public class TimeoutConfig(
  /** Absolute deadline for a single request, from send to response received. */
  public val requestTimeout: Duration = Duration.INFINITE,
  /** Time allowed to establish a TCP and TLS connection. */
  public val connectTimeout: Duration = 10.seconds,
  /** Maximum idle time between socket reads or writes during an in-flight request. */
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
