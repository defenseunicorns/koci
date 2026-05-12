/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

/**
 * Events emitted by [Repository.pull].
 *
 * [Progress] reports completion percentage (0..100) while bytes are flowing. The flow ends with
 * exactly one terminal event: [Completed] on success, [Failed] otherwise. Specific failure causes
 * (digest mismatch, registry error, unsupported content type, etc.) are logged inside koci — the
 * consumer just sees `Failed`.
 */
public sealed interface PullEvent {
  public data class Progress(val percent: Int) : PullEvent

  public data object Completed : PullEvent

  public data object Failed : PullEvent
}
