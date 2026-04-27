/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Digest

/**
 * Events emitted by [Layout.push].
 *
 * Mirrors the shape of [com.defenseunicorns.koci.api.PullEvent] for the internal push path; the
 * Repository layer translates these to public PullEvent variants for the caller.
 */
internal sealed interface PushEvent {
  data class Progress(val bytes: Int) : PushEvent

  data object Completed : PushEvent

  data class DigestMismatch(val expected: Descriptor, val actual: Digest) : PushEvent

  data class SizeMismatch(val expected: Descriptor, val actual: Long) : PushEvent
}
