/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

public enum class OciConstants(public val mediaType: String) {
  /** Media type for an image manifest (see [Manifest]). */
  MANIFEST("application/vnd.oci.image.manifest.v1+json"),

  /** Media type for a Docker manifest. */
  DOCKER_MANIFEST("application/vnd.docker.distribution.manifest.v2+json"),

  /** Media type for an image index (see [Index]). */
  INDEX("application/vnd.oci.image.index.v1+json");

  public companion object {
    public fun fromMediaType(mediaType: String?): OciConstants? =
      entries.firstOrNull { it.mediaType == mediaType }
  }
}
