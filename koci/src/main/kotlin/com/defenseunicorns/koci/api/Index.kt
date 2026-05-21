/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.internal.CopyOnWriteDescriptorArrayListSerializer
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * An OCI image index (`application/vnd.oci.image.index.v1+json`), referencing manifests for one or
 * more platforms.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
public class Index(
  /** OCI image index schema version; always 2. */
  @EncodeDefault public val schemaVersion: Int = 2,
  /** Media type of this document (`application/vnd.oci.image.index.v1+json`). */
  public val mediaType: String? = null,
  /** Platform-specific manifests referenced by this index. */
  @Serializable(with = CopyOnWriteDescriptorArrayListSerializer::class)
  public val manifests: CopyOnWriteArrayList<Descriptor> = CopyOnWriteArrayList(),
  /** Arbitrary metadata for the image index. */
  public val annotations: Annotations? = null,
  /** Optional reference to another manifest that this index was derived from. */
  public val subject: Descriptor? = null,
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
    var result = schemaVersion.hashCode() ?: 0
    result = 31 * result + (mediaType?.hashCode() ?: 0)
    result = 31 * result + manifests.hashCode()
    result = 31 * result + (annotations?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Index(schemaVersion=$schemaVersion, mediaType=$mediaType, manifests=$manifests, " +
      "annotations=$annotations)"
}
