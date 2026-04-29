/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import kotlinx.serialization.Serializable

/** Manifest provides [MANIFEST_MEDIA_TYPE] mediatype structure when marshalled to JSON. */
@Serializable
public class Manifest(
  /** schemaVersion is the image manifest schema that this image follows */
  public val schemaVersion: Int = 2,
  /** mediaType specifies the type of this document data structure e.g. [MANIFEST_MEDIA_TYPE] */
  public val mediaType: String? = null,
  /**
   * config references a configuration object for a container, by digest.
   *
   * The referenced configuration object is a JSON blob that the runtime uses to set up the
   * container.
   */
  public val config: Descriptor,
  /** layers is an indexed list of layers referenced by the manifest. */
  public val layers: List<Descriptor>,
  /** annotations contains arbitrary metadata for the image manifest. */
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
