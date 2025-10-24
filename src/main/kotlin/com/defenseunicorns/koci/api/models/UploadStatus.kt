/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

/** UploadStatus tracks the server-side state of an upload */
class UploadStatus(val location: String, var offset: Long, var minChunkSize: Long) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is UploadStatus) return false
    if (location != other.location) return false
    if (offset != other.offset) return false
    return minChunkSize == other.minChunkSize
  }

  override fun hashCode(): Int {
    var result = location.hashCode()
    result = 31 * result + offset.hashCode()
    result = 31 * result + minChunkSize.hashCode()
    return result
  }

  override fun toString(): String =
    "UploadStatus(location='$location', offset=$offset, minChunkSize=$minChunkSize)"
}
