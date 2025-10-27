/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * The layout directory is invalid or cannot be created.
 *
 * @param path The path that is invalid
 * @param reason Why it's invalid
 */
class InvalidLayout(val path: String, val reason: String) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is InvalidLayout) return false
    if (path != other.path) return false
    return reason == other.reason
  }

  override fun hashCode(): Int {
    var result = path.hashCode()
    result = 31 * result + reason.hashCode()
    return result
  }

  override fun toString(): String = "InvalidLayout(path='$path', reason='$reason')"
}
