package com.defenseunicorns.koci.api.errors

/**
   * The digest component of a reference is invalid.
   *
   * @param digest The invalid digest value
   * @param reason Why the digest is invalid
   */
  class InvalidDigest(val digest: String, val reason: String) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is InvalidDigest) return false
      if (digest != other.digest) return false
      return reason == other.reason
    }

    override fun hashCode(): Int {
      var result = digest.hashCode()
      result = 31 * result + reason.hashCode()
      return result
    }

    override fun toString(): String = "InvalidDigest(digest='$digest', reason='$reason')"
  }
