/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

import com.defenseunicorns.koci.api.models.Descriptor

/**
 * Unable to remove a descriptor because it's referenced by another artifact.
 *
 * @param descriptor The descriptor that cannot be removed
 * @param reason Why it cannot be removed
 */
class UnableToRemove(val descriptor: Descriptor, val reason: String) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is UnableToRemove) return false
    if (descriptor != other.descriptor) return false
    return reason == other.reason
  }

  override fun hashCode(): Int {
    var result = descriptor.hashCode()
    result = 31 * result + reason.hashCode()
    return result
  }

  override fun toString(): String = "UnableToRemove(descriptor=$descriptor, reason='$reason')"
}
