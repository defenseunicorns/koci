/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/**
 * Standard error codes defined by the OCI Distribution Spec.
 *
 * Registries return these in the `code` field of an [ActionableFailure] inside a [FailureResponse]
 * body when an operation fails. Internal-only — used purely to enrich the log line emitted by
 * [succeeded] when an HTTP call returns a non-success status.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Error Codes</a>
 */
internal enum class ErrorCode {
  /** Catch-all for codes the registry returned that aren't part of the spec we know. */
  UNKNOWN,
  BLOB_UNKNOWN,
  BLOB_UPLOAD_INVALID,
  BLOB_UPLOAD_UNKNOWN,
  DIGEST_INVALID,
  MANIFEST_BLOB_UNKNOWN,
  MANIFEST_INVALID,
  MANIFEST_UNKNOWN,
  NAME_INVALID,
  NAME_UNKNOWN,
  SIZE_INVALID,
  UNAUTHORIZED,
  DENIED,
  UNSUPPORTED,
  TOOMANYREQUESTS,
}
