/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.errors

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

/**
 * Represents an error response from an OCI registry.
 *
 * This class models the error response format defined in the OCI spec, which consists of an array
 * of errors. Each registry API error response must include at least one error.
 *
 * @property errors List of actionable errors returned by the registry
 * @property status HTTP status code of the response
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Errors</a>
 */
@Serializable
data class FailureResponse(
  val errors: List<ActionableFailure>,
  @Transient
  // TODO: this really the best way to initialize?
  var status: HttpStatusCode = HttpStatusCode(0, ErrorCode.UNKNOWN.toString()),
)

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
enum class ErrorCode {
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

/**
 * Represents a single error in an OCI registry error response.
 *
 * Each error contains a code identifying the error type, a message for the client, and optional
 * detail information specific to the error type.
 *
 * @property code Error code identifying the type of error
 * @property message Human-readable error message
 * @property detail Additional error-specific details (optional)
 */
@Serializable
data class ActionableFailure(
  val code: ErrorCode = ErrorCode.UNKNOWN,
  val message: String,
  val detail: JsonElement? = null,
)
