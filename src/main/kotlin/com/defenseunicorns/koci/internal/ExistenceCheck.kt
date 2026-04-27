/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Digest

/**
 * Outcome of [Layout.exists].
 *
 * [Present] means the blob is on disk and verifies against its descriptor. [Absent] means no blob
 * exists for the descriptor on disk. [PartialBySize] and [Corrupted] describe a blob that exists
 * but fails verification, and drive the resume-vs-discard logic in the pull path.
 */
internal sealed interface ExistenceCheck {
  data object Present : ExistenceCheck

  data object Absent : ExistenceCheck

  data class PartialBySize(val onDiskSize: Long) : ExistenceCheck

  data class Corrupted(val actualDigest: Digest) : ExistenceCheck
}
