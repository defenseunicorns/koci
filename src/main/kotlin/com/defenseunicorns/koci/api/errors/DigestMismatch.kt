package com.defenseunicorns.koci.api.errors

import com.defenseunicorns.koci.api.models.Descriptor
import com.defenseunicorns.koci.api.models.Digest

/**
   * The actual digest of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected digest
   * @param actual The actual digest of the content
   */
  class DigestMismatch(val expected: Descriptor, val actual: Digest?) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is DigestMismatch) return false
      if (expected != other.expected) return false
      return actual == other.actual
    }

    override fun hashCode(): Int {
      var result = expected.hashCode()
      result = 31 * result + actual.hashCode()
      return result
    }

    override fun toString(): String = "DigestMismatch(expected=$expected, actual=$actual)"
  }
