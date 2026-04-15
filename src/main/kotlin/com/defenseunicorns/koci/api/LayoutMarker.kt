/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import kotlinx.serialization.Serializable

/**
 * LayoutMarker is the structure in the "oci-layout" file ([IMAGE_LAYOUT_FILE]), found in the root
 * of an OCI Image Layout directory.
 */
@Serializable
public class LayoutMarker(public val imageLayoutVersion: String) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is LayoutMarker) return false
    return imageLayoutVersion == other.imageLayoutVersion
  }

  override fun hashCode(): Int = imageLayoutVersion.hashCode()

  override fun toString(): String = "LayoutMarker(imageLayoutVersion=$imageLayoutVersion)"
}
