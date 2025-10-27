/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * The tag component of a reference is invalid.
 *
 * @param tag The invalid tag value
 * @param reason Why the tag is invalid
 */
class InvalidTag(val tag: String, val reason: String) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is InvalidTag) return false
    if (tag != other.tag) return false
    return reason == other.reason
  }

  override fun hashCode(): Int {
    var result = tag.hashCode()
    result = 31 * result + reason.hashCode()
    return result
  }

  override fun toString(): String = "InvalidTag(tag='$tag', reason='$reason')"
}
