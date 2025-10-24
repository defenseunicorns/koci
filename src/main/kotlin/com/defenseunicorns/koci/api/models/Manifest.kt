/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import com.defenseunicorns.koci.models.Annotations
import kotlinx.serialization.Serializable

/** Manifest provides [MANIFEST_MEDIA_TYPE] mediatype structure when marshalled to JSON. */
@Serializable
class Manifest(
  /** schemaVersion is the image manifest schema that this image follows */
  override val schemaVersion: Int? = null,
  /** mediaType specifies the type of this document data structure e.g. [MANIFEST_MEDIA_TYPE] */
  val mediaType: String? = null,
  /**
   * config references a configuration object for a container, by digest.
   *
   * The referenced configuration object is a JSON blob that the runtime uses to set up the
   * container.
   */
  val config: Descriptor,
  /** layers is an indexed list of layers referenced by the manifest. */
  val layers: List<Descriptor>,
  /** annotations contains arbitrary metadata for the image manifest. */
  val annotations: Annotations? = null,
) : Versioned {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Manifest) return false
    if (schemaVersion != other.schemaVersion) return false
    if (mediaType != other.mediaType) return false
    if (config != other.config) return false
    if (layers != other.layers) return false
    return annotations == other.annotations
  }

  override fun hashCode(): Int {
    var result = schemaVersion.hashCode()
    result = 31 * result + (mediaType?.hashCode() ?: 0)
    result = 31 * result + config.hashCode()
    result = 31 * result + layers.hashCode()
    result = 31 * result + (annotations?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Manifest(schemaVersion=$schemaVersion, mediaType='$mediaType', config=$config, layers=$layers, annotations=$annotations)"
}
