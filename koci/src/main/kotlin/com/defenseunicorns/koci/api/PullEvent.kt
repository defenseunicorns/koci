/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

/**
 * Events emitted by [Repository.pull] and [Repository.push]. [Progress] carries a percentage from 0
 * to 100; the terminal event is `Progress(100)` on success or [Failed] on any error. Failure
 * details are logged internally; the consumer sees only `Failed`.
 */
public sealed interface PullEvent {
  /** Completion percentage, 0 to 100. */
  public data class Progress(val percent: Int) : PullEvent

  /** Terminal failure. */
  public data object Failed : PullEvent
}
