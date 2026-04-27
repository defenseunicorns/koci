/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor

/**
 * Outcome of [Layout.remove].
 *
 * [StillReferenced] carries the descriptor that could not be removed because another artifact still
 * references it.
 */
internal sealed interface RemoveOutcome {
  data object Removed : RemoveOutcome

  data object Absent : RemoveOutcome

  data class StillReferenced(val descriptor: Descriptor) : RemoveOutcome
}
