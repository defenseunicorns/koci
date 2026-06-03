/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Tracks per-descriptor byte counts against a fixed [total] and emits the latest integer percentage
 * (0 to 100) on [channel] after each [update]. Thread-safe; [update] never suspends because the
 * channel is conflated, so write loops stay unblocked when the consumer is slower than the
 * producer.
 */
internal class ProgressTracker(private val total: Long) {
  private val perDescriptor = ConcurrentHashMap<Descriptor, Long>()
  private val running = AtomicLong(0L)
  private val _channel = Channel<Int>(Channel.CONFLATED)

  /** Latest percentage. Iterate with `for (pct in tracker.channel)`. */
  val channel: ReceiveChannel<Int>
    get() = _channel

  /** Updates the byte count for [descriptor] and signals the new percentage. */
  fun update(descriptor: Descriptor, bytes: Long) {
    val old = perDescriptor.put(descriptor, bytes) ?: 0L
    val done = running.addAndGet(bytes - old)
    _channel.trySend((done * DONE_PERCENT / total).toInt())
  }

  /** Closes [channel]; iterators exit after draining remaining values. */
  fun close() = _channel.close()

  companion object {
    private const val DONE_PERCENT = 100
  }
}
