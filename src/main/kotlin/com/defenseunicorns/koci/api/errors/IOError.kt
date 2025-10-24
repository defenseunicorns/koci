package com.defenseunicorns.koci.api.errors

/**
   * An I/O error occurred.
   *
   * @param message Description of the I/O error
   * @param cause The underlying exception if available
   */
  class IOError(val message: String, val cause: Throwable? = null) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is IOError) return false
      if (message != other.message) return false
      return cause == other.cause
    }

    override fun hashCode(): Int {
      var result = message.hashCode()
      result = 31 * result + (cause?.hashCode() ?: 0)
      return result
    }

    override fun toString(): String = "IOError(message='$message', cause=$cause)"
  }
