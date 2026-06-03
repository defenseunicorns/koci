/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.internal.Regex.uploadRangeRegex
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders

/** Server-side state of a blob upload session. */
internal data class UploadStatus(val location: String, val offset: Long, val minChunkSize: Long)

/**
 * Parses [Headers] into an [UploadStatus] for a resumable upload. Returns `null` when `Location` or
 * `Range` is missing or malformed; callers treat that as a failed upload step.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#resuming-an-upload">OCI
 *   Distribution Spec: Resuming an Upload</a>
 */
internal fun Headers.toUploadStatus(): UploadStatus? {
  val location = this[HttpHeaders.Location] ?: return null
  val range = this[HttpHeaders.Range] ?: return null
  val offset = uploadRangeRegex.matchEntire(range)?.groupValues?.last() ?: return null

  // OCI-Chunk-Min-Length is optional on the response.
  val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

  return UploadStatus(location, offset.toLong(), minChunk)
}
