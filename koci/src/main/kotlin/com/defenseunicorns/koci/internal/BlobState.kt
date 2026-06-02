/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/**
 * On-disk state of a blob in the local [Layout] measured against its descriptor. Returned by
 * [Layout.inspect] in one pass so callers don't juggle separate existence and size probes.
 *
 * Corrupted and oversize files fold into [Absent] because the recovery action is the same: refetch
 * from byte zero. Use [Layout.remove] to delete the file outright.
 */
internal sealed interface BlobState {
  /** Full content present and digest-verified. */
  data object Present : BlobState

  /** Nothing usable on disk. Includes missing, zero-byte, oversize, and digest-mismatched files. */
  data object Absent : BlobState

  /**
   * A prefix is on disk. [bytesOnDisk] is the current size, in `1 until descriptor.size`. The
   * caller can resume via a `Range` request when the registry supports it.
   */
  data class Partial(val bytesOnDisk: Long) : BlobState
}
