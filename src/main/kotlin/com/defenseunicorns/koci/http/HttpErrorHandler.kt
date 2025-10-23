/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.http

import com.defenseunicorns.koci.models.errors.FailureResponse
import com.defenseunicorns.koci.models.errors.KociError
import com.defenseunicorns.koci.models.errors.KociResult
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Parses an HTTP error response into an OCIError.
 *
 * Attempts to parse JSON error responses from OCI registries according to the OCI Distribution
 * Specification error format. Falls back to generic HTTP errors if parsing fails.
 *
 * @param response The HTTP response with an error status code
 * @return OCIResult.Err containing the parsed error
 */
suspend fun <T> parseHTTPError(response: HttpResponse): KociResult<T> {
  // Try to parse OCI-compliant error response
  if (response.contentType() == ContentType.Application.Json) {
    try {
      val failureResponse: FailureResponse = response.body()
      failureResponse.status = response.status
      return KociResult.err(KociError.FromResponse(failureResponse))
    } catch (_: NoTransformationFoundException) {
      // Fall through to generic error
    }
  }

  // Generic HTTP error
  return KociResult.err(KociError.HTTPError(response.status.value, response.status.description))
}
