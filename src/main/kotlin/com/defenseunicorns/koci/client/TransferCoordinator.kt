/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.client

import co.touchlab.kermit.Logger
import com.defenseunicorns.koci.models.content.Descriptor
import com.defenseunicorns.koci.models.errors.OCIError
import com.defenseunicorns.koci.models.errors.OCIResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates transfers (downloads/uploads) across multiple concurrent operations to prevent
 * duplicate transfers of the same content.
 *
 * When multiple operations request the same descriptor, only one transfer executes while others
 * wait for completion. This prevents wasted bandwidth and ensures efficient resource usage during
 * concurrent operations.
 *
 * Thread-safe and designed for use across multiple coroutines.
 *
 * @param T The type of progress value emitted (e.g., Int for bytes downloaded, Long for bytes
 *   uploaded)
 */
internal class TransferCoordinator(private val logger: Logger) {
  private val inProgress = ConcurrentHashMap<Descriptor, TransferState>()
  private val mutex = Mutex()

  /**
   * Executes a transfer, coordinating with other concurrent transfers.
   *
   * If another operation is already transferring this descriptor, waits for that transfer to
   * complete instead of starting a duplicate. Otherwise, executes the provided transfer function.
   *
   * @param descriptor The descriptor to transfer
   * @param transfer Function that performs the actual transfer, emitting progress as OCIResult<T>
   * @return Flow emitting progress updates or errors
   */
  fun transfer(
    descriptor: Descriptor,
    transfer: suspend () -> Flow<OCIResult<Int>>,
  ): Flow<OCIResult<Int>> = flow {
    // Check if already transferring or claim the transfer
    val (state, shouldTransfer) =
      mutex.withLock {
        val state = inProgress.getOrPut(descriptor) { TransferState() }
        val shouldTransfer = !state.claimed && !state.completion.isCompleted
        if (shouldTransfer) {
          state.claimed = true // Claim it so others wait
        }
        state to shouldTransfer
      }

    try {
      if (shouldTransfer) {
        // We're the one transferring - execute the transfer
        try {
          var hasError = false

          transfer().collect { result ->
            when (result) {
              is OCIResult.Ok -> emit(result)
              is OCIResult.Err -> {
                hasError = true
                emit(result)
              }
            }
          }

          // Mark transfer complete
          mutex.withLock {
            state.succeeded = !hasError
            state.completion.complete(Unit)
          }
        } catch (e: Exception) {
          // Mark transfer failed
          mutex.withLock {
            logger.e(e) { "Transfer failed: ${descriptor.digest}" }
            state.succeeded = false
            state.completion.complete(Unit)
          }
          throw e
        }
      } else {
        // Someone else is transferring (or already finished) - wait if needed
        logger.d { "Waiting for transfer to complete: ${descriptor.digest}" }
        state.completion.await()

        // Check the result
        if (!state.succeeded) {
          logger.e { "Transfer failed: ${descriptor.digest}" }
          emit(OCIResult.err(OCIError.TransferFailed(descriptor)))
        }
      }
    } finally {
      // Decrement refcount and clean up if no one else is waiting
      val shouldRemove =
        mutex.withLock {
          val count = state.refCount.decrementAndGet()
          count <= 0
        }

      if (shouldRemove) {
        logger.d { "Removing from progress tracking: ${descriptor.digest}" }
        inProgress.remove(descriptor, state)
      }
    }
  }

  /**
   * Checks if a descriptor is currently being transferred.
   *
   * @param descriptor The descriptor to check
   * @return true if transfer is in progress, false otherwise
   */
  fun isTransferring(descriptor: Descriptor): Boolean {
    return inProgress[descriptor]?.completion?.isCompleted == false
  }

  /**
   * Returns the number of transfers currently in progress.
   *
   * Useful for monitoring and debugging.
   */
  fun activeTransfers(): Int {
    return inProgress.count { !it.value.completion.isCompleted }
  }

  /**
   * Represents the state of an in-progress transfer.
   *
   * @property completion Deferred that completes when transfer finishes (success or failure)
   * @property succeeded Whether the transfer succeeded (true) or failed (false)
   * @property claimed Whether someone has claimed this transfer (prevents duplicate downloads)
   * @property refCount Number of operations waiting on this transfer
   */
  private data class TransferState(
    val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    var succeeded: Boolean = false,
    var claimed: Boolean = false,
    val refCount: AtomicInteger = AtomicInteger(0),
  )
}
