/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.http.HttpStatusCode

/**
 * Structured non-2xx response handed to `onError`. [errors] mirrors the spec envelope when one is
 * present, otherwise it holds a single synthetic [ActionableFailure] with [ErrorCode.UNKNOWN] so
 * call sites never need to null-check.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Error Codes</a>
 */
internal data class FailureResponse(
  val status: HttpStatusCode,
  val errors: List<ActionableFailure>,
)
