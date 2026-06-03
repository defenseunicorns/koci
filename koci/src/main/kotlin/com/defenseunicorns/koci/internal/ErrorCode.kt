/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/**
 * OCI Distribution error codes. [UNKNOWN] is the fallback when the registry returns a code koci
 * doesn't model, or no parseable error envelope at all.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Error Codes</a>
 */
internal enum class ErrorCode {
  UNKNOWN,
  BLOB_UNKNOWN,
  BLOB_UPLOAD_INVALID,
  BLOB_UPLOAD_UNKNOWN,
  DIGEST_INVALID,
  MANIFEST_BLOB_UNKNOWN,
  MANIFEST_INVALID,
  MANIFEST_UNKNOWN,
  MANIFEST_UNVERIFIED,
  NAME_INVALID,
  NAME_UNKNOWN,
  PAGINATION_NUMBER_INVALID,
  RANGE_INVALID,
  SIZE_INVALID,
  TAG_INVALID,
  UNAUTHORIZED,
  DENIED,
  UNSUPPORTED,
}
