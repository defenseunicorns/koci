/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

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
class OciFailureResponse(
  val errors: List<OciActionableFailure>,
  @Transient
  // TODO: this really the best way to initialize?
  var status: HttpStatusCode = HttpStatusCode(0, OciErrorCode.UNKNOWN.toString()),
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is OciFailureResponse) return false
    if (errors != other.errors) return false
    return status == other.status
  }

  override fun hashCode(): Int {
    var result = errors.hashCode()
    result = 31 * result + status.hashCode()
    return result
  }

  override fun toString(): String = "OciFailureResponse(errors=$errors, status=$status)"
}
