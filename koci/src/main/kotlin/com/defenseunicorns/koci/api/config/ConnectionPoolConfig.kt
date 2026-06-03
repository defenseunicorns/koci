/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** HTTP connection pool tuning for the underlying engine. */
public class ConnectionPoolConfig(
  /** How long idle connections stay alive in the pool. */
  public val keepAlive: Duration = 5.minutes,
  /** Maximum number of pooled connections. */
  public val maxConnections: Int = 64,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as ConnectionPoolConfig

    if (keepAlive != other.keepAlive) return false
    if (maxConnections != other.maxConnections) return false

    return true
  }

  override fun hashCode(): Int {
    var result = keepAlive.hashCode()
    result = 31 * result + maxConnections
    return result
  }

  override fun toString(): String =
    "ConnectionPoolConfig(keepAlive=$keepAlive, maxConnections=$maxConnections)"
}
