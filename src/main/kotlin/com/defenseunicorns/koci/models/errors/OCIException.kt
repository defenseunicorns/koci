/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.errors

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode

/**
 * Exception types for OCI operations that should fail-fast.
 *
 * These exceptions are used in infrastructure/middleware layers (like Auth) where throwing is
 * appropriate. For domain-level errors that should be handled explicitly, use [OCIError] and
 * [OCIResult] instead.
 */
sealed class OCIException(message: String, cause: Throwable? = null) : Exception(message, cause) {

  /**
   * Thrown when an HTTP response has an unexpected status code.
   *
   * @param expected The expected HTTP status code
   * @param response The actual HTTP response received
   */
  class UnexpectedStatus(expected: HttpStatusCode, response: HttpResponse) :
    OCIException(
      "Expected status $expected but got ${response.status}. " + "Url: ${response.call.request.url}"
    )

  /**
   * Thrown when an authentication endpoint returns an empty token.
   *
   * @param response The HTTP response that contained the empty token
   */
  class EmptyTokenReturned(response: HttpResponse) :
    OCIException(
      "Authentication endpoint returned an empty token. " + "Url: ${response.call.request.url}"
    )
}
