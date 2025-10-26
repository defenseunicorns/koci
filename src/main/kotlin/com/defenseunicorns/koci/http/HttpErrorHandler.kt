/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.http

import com.defenseunicorns.koci.api.KociResult
import com.defenseunicorns.koci.api.errors.FromResponse
import com.defenseunicorns.koci.api.errors.HttpError
import com.defenseunicorns.koci.api.errors.OciFailureResponse
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
internal suspend fun <T> parseHTTPError(response: HttpResponse): T {
  // Try to parse OCI-compliant error response
  if (response.contentType() == ContentType.Application.Json) {
    try {
      val ociFailureResponse: OciFailureResponse = response.body()
      ociFailureResponse.status = response.status
      return ociFailureResponse
    } catch (_: Exception) {
      // Fall through to generic error
    }
  }

  // Generic HTTP error
  return KociResult.err(HttpError(response.status.value, response.status.description))
}
