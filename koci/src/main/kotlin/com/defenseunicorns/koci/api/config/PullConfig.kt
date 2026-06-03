/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

/** Pull tuning. */
public class PullConfig(
  /** Maximum number of blob downloads in flight at once. */
  public val concurrency: Int = 4
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as PullConfig

    return concurrency == other.concurrency
  }

  override fun hashCode(): Int {
    return concurrency
  }

  override fun toString(): String {
    return "PullConfig(concurrency=$concurrency)"
  }
}
