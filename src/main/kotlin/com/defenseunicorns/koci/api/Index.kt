/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.internal.CopyOnWriteDescriptorArrayListSerializer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable

/**
 * Index references manifests for various platforms.
 *
 * This structure provides [INDEX_MEDIA_TYPE] mediatype when marshalled to JSON.
 */
@Serializable
public class Index(
  public val schemaVersion: Int? = null,
  /** mediaType specifies the type of this document data structure e.g. [INDEX_MEDIA_TYPE] */
  public val mediaType: String? = null,
  /** manifests references platform specific manifests. */
  @Serializable(with = CopyOnWriteDescriptorArrayListSerializer::class)
  public val manifests: CopyOnWriteArrayList<Descriptor> = CopyOnWriteArrayList(),
  /** annotations contains arbitrary metadata for the image index. */
  public val annotations: Annotations? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Index) return false
    return schemaVersion == other.schemaVersion &&
      mediaType == other.mediaType &&
      manifests == other.manifests &&
      annotations == other.annotations
  }

  override fun hashCode(): Int {
    var result = schemaVersion?.hashCode() ?: 0
    result = 31 * result + (mediaType?.hashCode() ?: 0)
    result = 31 * result + manifests.hashCode()
    result = 31 * result + (annotations?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Index(schemaVersion=$schemaVersion, mediaType=$mediaType, manifests=$manifests, " +
      "annotations=$annotations)"
}
