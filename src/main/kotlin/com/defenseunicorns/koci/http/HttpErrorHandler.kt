/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.http

/**
 * Parses an HTTP error response into an OCIError.
 *
 * Attempts to parse JSON error responses from OCI registries according to the OCI Distribution
 * Specification error format. Falls back to generic HTTP errors if parsing fails.
 *
 * @param response The HTTP response with an error status code
 * @return OCIResult.Err containing the parsed error
 */
// internal suspend fun <T> parseHTTPError(response: HttpResponse): T {
//  // Try to parse OCI-compliant error response
//  if (response.contentType() == ContentType.Application.Json) {
//    try {
//      val ociFailureResponse: OciFailureResponse = response.body()
//      ociFailureResponse.status = response.status
//      return ociFailureResponse
//    } catch (_: Exception) {
//      // Fall through to generic error
//    }
//  }
//
//  // Generic HTTP error
//  return KociResult.err(HttpError(response.status.value, response.status.description))
// }
