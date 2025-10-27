/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

import com.defenseunicorns.koci.api.models.Descriptor

/**
 * The actual size of content doesn't match its descriptor.
 *
 * @param expected The descriptor with the expected size
 * @param actual The actual size of the content
 */
class SizeMismatch(val expected: Descriptor, val actual: Long) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SizeMismatch) return false
    if (expected != other.expected) return false
    return actual == other.actual
  }

  override fun hashCode(): Int {
    var result = expected.hashCode()
    result = 31 * result + actual.hashCode()
    return result
  }

  override fun toString(): String = "SizeMismatch(expected=$expected, actual=$actual)"
}
