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
sealed class KociResult<out T> {
  /**
   * Represents a successful result containing a value.
   *
   * @param value The successful result value
   */
  data class Ok<out T>(val value: T) : KociResult<T>()

  /**
   * Represents a failed result containing an error.
   *
   * @param error The error that occurred
   */
  data class Err(val error: KociError) : KociResult<Nothing>()

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
  fun errorOrNull(): KociError? =
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
  inline fun <R> map(transform: (T) -> R): KociResult<R> =
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
  inline fun <R> flatMap(transform: (T) -> KociResult<R>): KociResult<R> =
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
  inline fun mapErr(transform: (KociError) -> KociError): KociResult<T> =
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
  inline fun <R> fold(onErr: (KociError) -> R, onOk: (T) -> R): R =
    when (this) {
      is Ok -> onOk(value)
      is Err -> onErr(error)
    }

  companion object {
    /** Creates a successful result. */
    fun <T> ok(value: T): KociResult<T> = Ok(value)

    /** Creates a failed result. */
    fun <T> err(error: KociError): KociResult<T> = Err(error)
  }
}

/**
 * Sealed interface for domain errors that can occur in OCI operations.
 *
 * These are expected failure modes, not exceptional conditions. Each error type provides structured
 * information about what went wrong.
 */
sealed interface KociError {
  /**
   * A blob was not found in the layout.
   *
   * @param descriptor The descriptor of the missing blob
   */
  data class BlobNotFound(val descriptor: Descriptor) : KociError

  /**
   * The actual size of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected size
   * @param actual The actual size of the content
   */
  data class SizeMismatch(val expected: Descriptor, val actual: Long) : KociError

  /**
   * The actual digest of content doesn't match its descriptor.
   *
   * @param expected The descriptor with the expected digest
   * @param actual The actual digest of the content
   */
  data class DigestMismatch(val expected: Descriptor, val actual: Digest) : KociError

  /**
   * Unable to remove a descriptor because it's referenced by another artifact.
   *
   * @param descriptor The descriptor that cannot be removed
   * @param reason Why it cannot be removed
   */
  data class UnableToRemove(val descriptor: Descriptor, val reason: String) : KociError

  /**
   * A manifest with an unsupported media type was encountered.
   *
   * @param mediaType The unsupported media type
   * @param location Where the unsupported manifest was found
   */
  data class UnsupportedManifest(val mediaType: String, val location: String) : KociError

  /**
   * A descriptor could not be resolved using the given criteria.
   *
   * @param criteria Description of what was being searched for
   */
  data class DescriptorNotFound(val criteria: String) : KociError

  /**
   * The layout directory is invalid or cannot be created.
   *
   * @param path The path that is invalid
   * @param reason Why it's invalid
   */
  data class InvalidLayout(val path: String, val reason: String) : KociError

  /**
   * An I/O error occurred.
   *
   * @param message Description of the I/O error
   * @param cause The underlying exception if available
   */
  data class IOError(val message: String, val cause: Throwable? = null) : KociError

  /**
   * An HTTP error from a registry.
   *
   * @param statusCode The HTTP status code
   * @param message Description of the error
   */
  data class HTTPError(val statusCode: Int, val message: String) : KociError

  /**
   * An error response from an OCI registry.
   *
   * @param response The failure response from the registry
   */
  data class FromResponse(val response: FailureResponse) : KociError

  /**
   * The registry component of a reference is invalid.
   *
   * @param registry The invalid registry value
   * @param reason Why the registry is invalid
   */
  data class InvalidRegistry(val registry: String, val reason: String) : KociError

  /**
   * The repository component of a reference is invalid.
   *
   * @param repository The invalid repository value
   * @param reason Why the repository is invalid
   */
  data class InvalidRepository(val repository: String, val reason: String) : KociError

  /**
   * The tag component of a reference is invalid.
   *
   * @param tag The invalid tag value
   * @param reason Why the tag is invalid
   */
  data class InvalidTag(val tag: String, val reason: String) : KociError

  /**
   * The digest component of a reference is invalid.
   *
   * @param digest The invalid digest value
   * @param reason Why the digest is invalid
   */
  data class InvalidDigest(val digest: String, val reason: String) : KociError

  /**
   * A transfer (download/upload) failed in another concurrent operation.
   *
   * When multiple operations attempt to transfer the same descriptor, only one executes the
   * transfer. If that transfer fails, other waiting operations receive this error.
   *
   * @param descriptor The descriptor that failed to transfer
   */
  data class TransferFailed(val descriptor: Descriptor) : KociError

  /**
   * A generic error for cases not covered by specific error types.
   *
   * @param message Description of the error
   */
  data class Generic(val message: String) : KociError
}
