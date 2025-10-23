/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.content

import com.defenseunicorns.koci.models.Annotations
import kotlinx.serialization.Serializable

/** Manifest provides [MANIFEST_MEDIA_TYPE] mediatype structure when marshalled to JSON. */
@Serializable
data class Manifest(
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
) : Versioned
