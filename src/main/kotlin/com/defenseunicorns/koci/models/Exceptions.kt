/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models

import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType

/**
 * Base exception class for OCI-related errors.
 *
 * This sealed class provides a hierarchy of exceptions for different types of errors that can occur
 * when interacting with OCI registries, including content verification failures, API errors, and
 * authentication issues.
 */
sealed class OCIException(message: String) : Exception(message) {
  /**
   * Thrown when a manifest with an unsupported media type is encountered.
   *
   * @param endpoint The URL endpoint that returned the unsupported manifest
   * @param mediaType The unsupported content type
   */
  class ManifestNotSupported(endpoint: Url, mediaType: ContentType?) :
    OCIException("Unsupported content type returned from $endpoint: $mediaType")

  /**
   * Thrown when the actual size of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected size
   * @param actual The actual size of the content
   */
  class SizeMismatch(val expected: Descriptor, val actual: Long) :
    OCIException("Size mismatch: expected (${expected.size}) got ($actual)")

  /**
   * Thrown when the actual digest of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected digest
   * @param actual The actual digest of the content
   */
  class DigestMismatch(val expected: Descriptor, val actual: Digest) :
    OCIException("Digest mismatch: expected (${expected.digest}) got ($actual)")

  /**
   * Thrown when a requested platform cannot be found in an index manifest.
   *
   * @param index The index manifest that was searched
   */
  class PlatformNotFound(val index: Index) :
    OCIException("in [${index.manifests.map { it.platform }.joinToString()}]")

  /**
   * Thrown when an HTTP response has an unexpected status code.
   *
   * @param expected The expected HTTP status code
   * @param response The actual HTTP response
   */
  class UnexpectedStatus(val expected: HttpStatusCode, response: HttpResponse) :
    OCIException("Expected ($expected) got (${response.status})")

  /**
   * Thrown when an error response is received from an OCI registry.
   *
   * @param fr The parsed failure response from the registry
   */
  class FromResponse(val fr: FailureResponse) :
    OCIException(fr.errors.joinToString { "${it.code}: ${it.message}" })

  /**
   * Thrown when an authentication response contains an empty token.
   *
   * @param response The HTTP response that should have contained a token
   */
  class EmptyTokenReturned(val response: HttpResponse) :
    OCIException(
      "${response.call.request.method} ${response.call.request.url}: empty token returned"
    )

  /**
   * Thrown when content cannot be removed from a registry.
   *
   * @param descriptor The descriptor of the content that could not be removed
   * @param reason The reason for the failure
   */
  class UnableToRemove(val descriptor: Descriptor, val reason: String) :
    OCIException("Unable to remove $descriptor: $reason")

  /**
   * Thrown when a pull operation completes but validation fails.
   *
   * @param ref The reference that was being pulled
   */
  class IncompletePull(ref: Reference) :
    OCIException(
      "Pull operation completed, but was unsuccessful in validating $ref was pulled fully"
    )
}

/**
 * Attempts to parse and throw an error from a 4XX HTTP response.
 *
 * This function handles error responses from OCI registries by parsing the JSON error response and
 * throwing an appropriate exception. It is used to convert HTTP errors into typed exceptions for
 * better error handling.
 *
 * @param response The HTTP response with a 4XX status code
 * @throws OCIException.FromResponse if the response contains a valid error payload
 */
internal suspend fun attemptThrow4XX(response: HttpResponse) {
  require(response.status.value in 400..499) {
    "Attempted to throw when status was not >=400 && <=499"
  }

  if (response.contentType() == ContentType.Application.Json) {
    try {
      val fr: FailureResponse = response.body()
      fr.status = response.status
      throw OCIException.FromResponse(fr)
    } catch (_: NoTransformationFoundException) {
      return
    }
  }
}
