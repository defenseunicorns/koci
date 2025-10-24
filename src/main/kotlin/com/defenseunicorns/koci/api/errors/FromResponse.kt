package com.defenseunicorns.koci.api.errors

/**
   * An error response from an OCI registry.
   *
   * @param response The failure response from the registry
   */
  class FromResponse(val response: OciFailureResponse) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is FromResponse) return false
      return response == other.response
    }

    override fun hashCode(): Int = response.hashCode()

    override fun toString(): String = "FromResponse(response=$response)"
  }
