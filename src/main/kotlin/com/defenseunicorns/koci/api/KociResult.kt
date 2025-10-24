/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.errors.KociError

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
  class Ok<out T>(val value: T) : KociResult<T>() {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Ok<*>) return false
      return value == other.value
    }

    override fun hashCode(): Int = value?.hashCode() ?: 0

    override fun toString(): String = "Ok(value=$value)"
  }

  /**
   * Represents a failed result containing an error.
   *
   * @param error The error that occurred
   */
  class Err(val error: KociError) : KociResult<Nothing>() {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Err) return false
      return error == other.error
    }

    override fun hashCode(): Int = error.hashCode()

    override fun toString(): String = "Err(error=$error)"
  }

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
