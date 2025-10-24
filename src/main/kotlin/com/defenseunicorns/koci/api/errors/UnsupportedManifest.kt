package com.defenseunicorns.koci.api.errors

/**
   * A manifest with an unsupported media type was encountered.
   *
   * @param mediaType The unsupported media type
   * @param location Where the unsupported manifest was found
   */
  class UnsupportedManifest(val mediaType: String, val location: String) : KociError {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is UnsupportedManifest) return false
      if (mediaType != other.mediaType) return false
      return location == other.location
    }

    override fun hashCode(): Int {
      var result = mediaType.hashCode()
      result = 31 * result + location.hashCode()
      return result
    }

    override fun toString(): String =
      "UnsupportedManifest(mediaType='$mediaType', location='$location')"
  }
