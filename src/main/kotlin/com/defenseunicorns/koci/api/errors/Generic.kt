package com.defenseunicorns.koci.api.errors

/**
   * A generic error for cases not covered by specific error types.
   *
   * @param message Description of the error
   */
  class Generic(val message: String) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Generic) return false
      return message == other.message
    }

    override fun hashCode(): Int = message.hashCode()

    override fun toString(): String = "Generic(message='$message')"
  }
