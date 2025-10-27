/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * Standard error codes defined by the OCI spec.
 *
 * These codes identify the error type that occurred on the registry. Error codes must be uppercase,
 * using only alphanumeric characters and underscores.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Error Codes</a>
 */
enum class OciErrorCode {
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
