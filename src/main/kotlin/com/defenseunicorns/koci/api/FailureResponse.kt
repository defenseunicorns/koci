/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
public class FailureResponse(public val errors: List<ActionableFailure>) {
  @Transient
  public var status: HttpStatusCode = HttpStatusCode(0, ErrorCode.UNKNOWN.toString())
    internal set

  override fun toString(): String = "FailureResponse(errors=$errors, status=$status)"
}
