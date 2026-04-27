/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/** UploadStatus tracks the server-side state of an upload. */
internal data class UploadStatus(val location: String, var offset: Long, var minChunkSize: Long)

/**
 * Extracts upload status from HTTP response headers for resumable uploads.
 *
 * Parses Location and Range headers to determine upload state and handles the optional
 * OCI-Chunk-Min-Length header.
 *
 * @return [UploadStatus] with location URL, byte offset, and minimum chunk size
 * @throws IllegalStateException if required headers are missing or malformed
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#resuming-an-upload">OCI
 *   Distribution Spec: Resuming an Upload</a>
 */
internal fun Headers.toUploadStatus(): UploadStatus {
  val location = checkNotNull(this[HttpHeaders.Location]) { "missing Location header" }
  val range = checkNotNull(this[HttpHeaders.Range]) { "missing Range header" }
  val re = Regex("^([0-9]+)-([0-9]+)$")
  val offset = checkNotNull(re.matchEntire(range)?.groupValues?.last()) { "invalid Range header" }

  // this header MAY not exist
  val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

  return UploadStatus(location, offset.toLong(), minChunk)
}
