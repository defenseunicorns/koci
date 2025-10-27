/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * An HTTP error from a registry.
 *
 * @param statusCode The HTTP status code
 * @param message Description of the error
 */
class HttpError(val statusCode: Int, val message: String) : KociError {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is HttpError) return false
    if (statusCode != other.statusCode) return false
    return message == other.message
  }

  override fun hashCode(): Int {
    var result = statusCode
    result = 31 * result + message.hashCode()
    return result
  }

  override fun toString(): String = "HTTPError(statusCode=$statusCode, message='$message')"
}
