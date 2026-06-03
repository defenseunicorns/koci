/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

/** Push tuning. */
public class PushConfig(
  /** Maximum number of blob uploads in flight at once. */
  public val concurrency: Int = 4,
  /**
   * Minimum chunk size for chunked uploads, in bytes. When a registry advertises its own minimum
   * via `OCI-Chunk-Min-Length`, the registry's value is used instead. Leave `null` to defer
   * entirely to the registry.
   */
  public val minChunkSize: Long? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as PushConfig

    if (concurrency != other.concurrency) return false
    if (minChunkSize != other.minChunkSize) return false

    return true
  }

  override fun hashCode(): Int {
    var result = concurrency
    result = 31 * result + (minChunkSize?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String {
    return "PushConfig(concurrency=$concurrency, minChunkSize=$minChunkSize)"
  }
}
