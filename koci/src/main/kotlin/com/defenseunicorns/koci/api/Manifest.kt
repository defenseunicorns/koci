/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** An OCI image manifest (`application/vnd.oci.image.manifest.v1+json`). */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public class Manifest(
  /** OCI image manifest schema version; always 2. */
  @EncodeDefault public val schemaVersion: Int = 2,
  /** Media type of this document (`application/vnd.oci.image.manifest.v1+json`). */
  public val mediaType: String? = null,
  /** Descriptor of the config blob the runtime uses to set up the container. */
  public val config: Descriptor,
  /** Ordered list of layer descriptors that make up the image filesystem. */
  public val layers: List<Descriptor>,
  /** Arbitrary key/value metadata for the manifest. */
  public val annotations: Annotations? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Manifest) return false
    return schemaVersion == other.schemaVersion &&
      mediaType == other.mediaType &&
      config == other.config &&
      layers == other.layers &&
      annotations == other.annotations
  }

  override fun hashCode(): Int {
    var result = schemaVersion?.hashCode() ?: 0
    result = 31 * result + (mediaType?.hashCode() ?: 0)
    result = 31 * result + config.hashCode()
    result = 31 * result + layers.hashCode()
    result = 31 * result + (annotations?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Manifest(schemaVersion=$schemaVersion, mediaType=$mediaType, config=$config, " +
      "layers=$layers, annotations=$annotations)"
}
