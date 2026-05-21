/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Retry behavior for HTTP requests. Retries fire on exceptions, 5xx responses, and any non-success
 * status code.
 */
public sealed class RetryPolicy {
  internal abstract val maxRetries: Int

  /** No retries. The initial request is the only attempt. */
  public data object None : RetryPolicy() {
    override val maxRetries: Int = 0
  }

  /**
   * Waits a fixed [delay] before each retry, plus up to [randomization] of jitter.
   *
   * @param maxRetries Maximum retry attempts after the initial failure.
   */
  public class Linear(
    override val maxRetries: Int,
    internal val delay: Duration = 1.seconds,
    internal val randomization: Duration = 1.seconds,
  ) : RetryPolicy()

  /**
   * Grows the wait geometrically: `baseDelay * base^(N-1)` before retry N, capped at [maxDelay],
   * plus up to [randomization] of jitter.
   *
   * @param maxRetries Maximum retry attempts after the initial failure.
   */
  public class Exponential(
    override val maxRetries: Int,
    internal val base: Double = 2.0,
    internal val baseDelay: Duration = 1.seconds,
    internal val maxDelay: Duration = 60.seconds,
    internal val randomization: Duration = 1.seconds,
  ) : RetryPolicy()

  /**
   * Computes the delay via [compute], called with the 1-based attempt number.
   *
   * @param maxRetries Maximum retry attempts after the initial failure.
   */
  public class Custom(
    override val maxRetries: Int,
    internal val compute: (attempt: Int) -> Duration,
  ) : RetryPolicy()
}
