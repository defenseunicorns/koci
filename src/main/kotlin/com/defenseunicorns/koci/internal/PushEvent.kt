/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/**
 * Events emitted by [Layout.push].
 *
 * Mirrors [com.defenseunicorns.koci.api.PullEvent]'s shape so [Repository.copy] can map directly
 * one-to-one. Specific failure reasons (size mismatch / digest mismatch) are logged inside push
 * before [Failed] is emitted.
 */
internal sealed interface PushEvent {
  data class Progress(val bytes: Int) : PushEvent

  data object Completed : PushEvent

  data object Failed : PushEvent
}
