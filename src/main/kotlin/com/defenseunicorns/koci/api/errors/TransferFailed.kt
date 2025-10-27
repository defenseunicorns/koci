/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

import com.defenseunicorns.koci.api.models.Descriptor

/**
 * A transfer (download/upload) failed in another concurrent operation.
 *
 * When multiple operations attempt to transfer the same descriptor, only one executes the transfer.
 * If that transfer fails, other waiting operations receive this error.
 *
 * @param descriptor The descriptor that failed to transfer
 */
class TransferFailed(val descriptor: Descriptor) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TransferFailed) return false
    return descriptor == other.descriptor
  }

  override fun hashCode(): Int = descriptor.hashCode()

  override fun toString(): String = "TransferFailed(descriptor=$descriptor)"
}
