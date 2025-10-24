package com.defenseunicorns.koci.api.errors

import com.defenseunicorns.koci.api.models.Descriptor

/**
   * A blob was not found in the layout.
   *
   * @param descriptor The descriptor of the missing blob
   */
  class BlobNotFound(val descriptor: Descriptor) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is BlobNotFound) return false
      return descriptor == other.descriptor
    }

    override fun hashCode(): Int = descriptor.hashCode()

    override fun toString(): String = "BlobNotFound(descriptor=$descriptor)"
  }
