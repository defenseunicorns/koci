/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Strategy that determines how long to wait between retry attempts.
 *
 * Use [None] for no backoff (immediate retry), [Linear] for a fixed delay, [Exponential] for
 * exponential growth, or [Custom] to plug in a custom supplied function.
 */
public sealed class BackOffPolicy {
  /**
   * Returns the delay to wait before the [attempt]-th retry. [attempt] is 1-based: the delay before
   * the first retry is `nextDelay(1)`.
   */
  internal abstract fun nextDelay(attempt: Int): Duration

  /** No backoff — retries fire immediately. */
  public data object None : BackOffPolicy() {
    override fun nextDelay(attempt: Int): Duration = Duration.ZERO
  }

  /**
   * Linear backoff: attempt N waits `base * N`, capped at [max].
   *
   * @param base delay added per attempt
   * @param max upper bound on the returned delay
   */
  public class Linear(private val base: Duration = BASE, private val max: Duration = MAX) :
    BackOffPolicy() {
    override fun nextDelay(attempt: Int): Duration = (base * attempt).coerceAtMost(max)
  }

  /**
   * Exponential backoff: attempt N waits `base * factor^(N-1)`, capped at [max].
   *
   * @param base delay before the first retry
   * @param factor multiplier applied per additional attempt
   * @param max upper bound on the returned delay
   */
  public class Exponential(
    private val base: Duration = BASE,
    private val factor: Double = 2.0,
    private val max: Duration = MAX,
  ) : BackOffPolicy() {
    override fun nextDelay(attempt: Int): Duration =
      (base * factor.pow(attempt - 1)).coerceAtMost(max)
  }

  /**
   * Caller-supplied policy. The [compute] lambda receives the attempt number and returns the delay
   * to wait before that attempt.
   */
  public class Custom(private val compute: (attempt: Int) -> Duration) : BackOffPolicy() {
    override fun nextDelay(attempt: Int): Duration = compute(attempt)
  }

  private companion object {
    private val BASE = 100.milliseconds
    private val MAX = 30.seconds
  }
}
