/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.errors

import com.defenseunicorns.koci.models.content.Descriptor
import com.defenseunicorns.koci.models.content.Digest

/**
 * A result type for operations that can fail with domain errors.
 *
 * Unlike Kotlin's [Result], this type uses a sealed interface for errors instead of exceptions,
 * making error handling explicit and composable without throwing.
 */
sealed class OCIResult<out T> {
  /**
   * Represents a successful result containing a value.
   *
   * @param value The successful result value
   */
  data class Ok<out T>(val value: T) : OCIResult<T>()

  /**
   * Represents a failed result containing an error.
   *
   * @param error The error that occurred
   */
  data class Err(val error: OCIError) : OCIResult<Nothing>()

  /** Returns true if this is an [Ok] result. */
  fun isOk(): Boolean = this is Ok

  /** Returns true if this is an [Err] result. */
  fun isErr(): Boolean = this is Err

  /**
   * Returns the value if [Ok], or null if [Err].
   *
   * @return The value or null
   */
  fun getOrNull(): T? =
    when (this) {
      is Ok -> value
      is Err -> null
    }

  /**
   * Returns the error if [Err], or null if [Ok].
   *
   * @return The error or null
   */
  fun errorOrNull(): OCIError? =
    when (this) {
      is Ok -> null
      is Err -> error
    }

  /**
   * Transforms the value if [Ok], or propagates the error.
   *
   * @param transform Function to transform the success value
   * @return A new result with the transformed value or the same error
   */
  inline fun <R> map(transform: (T) -> R): OCIResult<R> =
    when (this) {
      is Ok -> Ok(transform(value))
      is Err -> this
    }

  /**
   * Transforms the value if [Ok] into another result, or propagates the error.
   *
   * @param transform Function to transform the success value into a new result
   * @return The new result or the same error
   */
  inline fun <R> flatMap(transform: (T) -> OCIResult<R>): OCIResult<R> =
    when (this) {
      is Ok -> transform(value)
      is Err -> this
    }

  /**
   * Transforms the error if [Err], or propagates the success.
   *
   * @param transform Function to transform the error
   * @return A new result with the transformed error or the same value
   */
  inline fun mapErr(transform: (OCIError) -> OCIError): OCIResult<T> =
    when (this) {
      is Ok -> this
      is Err -> Err(transform(error))
    }

  /**
   * Applies one of two functions depending on the result.
   *
   * @param onErr Function to apply if this is an error
   * @param onOk Function to apply if this is a success
   * @return The result of applying the appropriate function
   */
  inline fun <R> fold(onErr: (OCIError) -> R, onOk: (T) -> R): R =
    when (this) {
      is Ok -> onOk(value)
      is Err -> onErr(error)
    }

  companion object {
    /** Creates a successful result. */
    fun <T> ok(value: T): OCIResult<T> = Ok(value)

    /** Creates a failed result. */
    fun <T> err(error: OCIError): OCIResult<T> = Err(error)
  }
}

/**
 * Sealed interface for domain errors that can occur in OCI operations.
 *
 * These are expected failure modes, not exceptional conditions. Each error type provides structured
 * information about what went wrong.
 */
sealed interface OCIError {
  /**
   * A blob was not found in the layout.
   *
   * @param descriptor The descriptor of the missing blob
   */
  data class BlobNotFound(val descriptor: Descriptor) : OCIError

  /**
   * The actual size of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected size
   * @param actual The actual size of the content
   */
  data class SizeMismatch(val expected: Descriptor, val actual: Long) : OCIError

  /**
   * The actual digest of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected digest
   * @param actual The actual digest of the content
   */
  data class DigestMismatch(val expected: Descriptor, val actual: Digest) : OCIError

  /**
   * Unable to remove a descriptor because it's referenced by another artifact.
   *
   * @param descriptor The descriptor that cannot be removed
   * @param reason Why it cannot be removed
   */
  data class UnableToRemove(val descriptor: Descriptor, val reason: String) : OCIError

  /**
   * A manifest with an unsupported media type was encountered.
   *
   * @param mediaType The unsupported media type
   * @param location Where the unsupported manifest was found
   */
  data class UnsupportedManifest(val mediaType: String, val location: String) : OCIError

  /**
   * A descriptor could not be resolved using the given criteria.
   *
   * @param criteria Description of what was being searched for
   */
  data class DescriptorNotFound(val criteria: String) : OCIError

  /**
   * The layout directory is invalid or cannot be created.
   *
   * @param path The path that is invalid
   * @param reason Why it's invalid
   */
  data class InvalidLayout(val path: String, val reason: String) : OCIError

  /**
   * An I/O error occurred.
   *
   * @param message Description of the I/O error
   * @param cause The underlying exception if available
   */
  data class IOError(val message: String, val cause: Throwable? = null) : OCIError

  /**
   * An HTTP error from a registry.
   *
   * @param statusCode The HTTP status code
   * @param message Description of the error
   */
  data class HTTPError(val statusCode: Int, val message: String) : OCIError

  /**
   * An error response from an OCI registry.
   *
   * @param response The failure response from the registry
   */
  data class FromResponse(val response: FailureResponse) : OCIError

  /**
   * A generic error for cases not covered by specific error types.
   *
   * @param message Description of the error
   */
  data class Generic(val message: String) : OCIError
}
