/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

/** Push-side tuning. */
public class PushConfig(
  /** Maximum number of parallel pushes. */
  public val concurrency: Int = 4,

  /**
   * Minimum chunk size for chunked uploads, in bytes.
   *
   * Per the OCI Distribution spec, registries MAY advertise their own minimum via the
   * `OCI-Chunk-Min-Length` header on the upload location response — when the registry advertises a
   * minimum, that value wins. `null` means honor whatever the registry reports (or upload in a
   * single PUT when chunking isn't required).
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
