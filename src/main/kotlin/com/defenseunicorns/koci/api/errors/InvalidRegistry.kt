/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * The registry component of a reference is invalid.
 *
 * @param registry The invalid registry value
 * @param reason Why the registry is invalid
 */
class InvalidRegistry(val registry: String, val reason: String) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is InvalidRegistry) return false
    if (registry != other.registry) return false
    return reason == other.reason
  }

  override fun hashCode(): Int {
    var result = registry.hashCode()
    result = 31 * result + reason.hashCode()
    return result
  }

  override fun toString(): String = "InvalidRegistry(registry='$registry', reason='$reason')"
}
