package com.defenseunicorns.koci.api.errors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a single error in an OCI registry error response.
 *
 * Each error contains a code identifying the error type, a message for the client, and optional
 * detail information specific to the error type.
 *
 * @property code Error code identifying the type of error
 * @property message Human-readable error message
 * @property detail Additional error-specific details (optional)
 */
@Serializable
class OciActionableFailure(
  val code: OciErrorCode = OciErrorCode.UNKNOWN,
  val message: String,
  val detail: JsonElement? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is OciActionableFailure) return false
    if (code != other.code) return false
    if (message != other.message) return false
    return detail == other.detail
  }

  override fun hashCode(): Int {
    var result = code.hashCode()
    result = 31 * result + message.hashCode()
    result = 31 * result + (detail?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String = "OciActionableFailure(code=$code, message='$message', detail=$detail)"
}
