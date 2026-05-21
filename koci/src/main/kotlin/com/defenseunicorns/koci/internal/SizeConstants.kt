/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

internal object SizeConstants {
  /** Per-operation read/write buffer. 256 KB balances syscall count against heap pressure. */
  const val IO_BUFFER_SIZE = 256L * 1024

  /**
   * Default chunk size for chunked blob uploads when the registry does not advertise
   * `OCI-Chunk-Min-Length`. 5 MB is a sweet spot: large enough to amortize per-chunk overhead
   * across slow links, small enough that intermediaries don't drop the connection.
   */
  const val DEFAULT_PUSH_CHUNK_SIZE = 5L * 1024 * 1024
}
