package com.defenseunicorns.koci.models.content

import kotlinx.serialization.Serializable

/**
 * LayoutMarker is the structure in the "oci-layout" file, found in the root of an OCI Layout
 * directory
 */
@Serializable
data class LayoutMarker(val imageLayoutVersion: String)
