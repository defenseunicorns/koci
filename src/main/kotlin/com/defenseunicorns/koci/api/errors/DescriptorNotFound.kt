/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * A descriptor could not be resolved using the given criteria.
 *
 * @param criteria Description of what was being searched for
 */
class DescriptorNotFound(val criteria: String) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DescriptorNotFound) return false
    return criteria == other.criteria
  }

  override fun hashCode(): Int = criteria.hashCode()

  override fun toString(): String = "DescriptorNotFound(criteria='$criteria')"
}
