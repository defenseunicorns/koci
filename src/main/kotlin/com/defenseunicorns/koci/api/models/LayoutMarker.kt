/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import kotlinx.serialization.Serializable

/**
 * LayoutMarker is the structure in the "oci-layout" file, found in the root of an OCI Layout
 * directory
 */
@Serializable class LayoutMarker(val imageLayoutVersion: String) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is LayoutMarker) return false
    return imageLayoutVersion == other.imageLayoutVersion
  }

  override fun hashCode(): Int {
    return imageLayoutVersion.hashCode()
  }

  override fun toString(): String = "LayoutMarker(imageLayoutVersion='$imageLayoutVersion')"
}
