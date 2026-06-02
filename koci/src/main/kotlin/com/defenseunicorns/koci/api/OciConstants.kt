/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

public object OciConstants {

  /** Media type for an image manifest (see [Manifest]). */
  public const val MANIFEST_MEDIA_TYPE: String = "application/vnd.oci.image.manifest.v1+json"

  /** Media type for an image index (see [Index]). */
  public const val INDEX_MEDIA_TYPE: String = "application/vnd.oci.image.index.v1+json"
}
