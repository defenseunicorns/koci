/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

internal object SizeConstants {
  /** Per-operation read/write buffer. */
  const val IO_BUFFER_SIZE = 256L * 1024

  /**
   * Default chunk size for chunked blob uploads when the registry does not advertise
   * `OCI-Chunk-Min-Length`.
   */
  const val DEFAULT_PUSH_CHUNK_SIZE = 5L * 1024 * 1024
}
