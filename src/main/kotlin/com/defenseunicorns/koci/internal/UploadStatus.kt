/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.internal.Regex.uploadRangeRegex
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/** UploadStatus tracks the server-side state of an upload. */
internal data class UploadStatus(val location: String, var offset: Long, var minChunkSize: Long)

/**
 * Extracts upload status from HTTP response headers for resumable uploads.
 *
 * Parses Location and Range headers to determine upload state and handles the optional
 * OCI-Chunk-Min-Length header. Returns null when the registry omits or malforms a required header —
 * callers treat null as a failed upload step and surface it accordingly.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#resuming-an-upload">OCI
 *   Distribution Spec: Resuming an Upload</a>
 */
internal fun Headers.toUploadStatus(): UploadStatus? {
  val location = this[HttpHeaders.Location] ?: return null
  val range = this[HttpHeaders.Range] ?: return null
  val offset = uploadRangeRegex.matchEntire(range)?.groupValues?.last() ?: return null

  // this header MAY not exist
  val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

  return UploadStatus(location, offset.toLong(), minChunk)
}
