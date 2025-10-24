/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import com.defenseunicorns.koci.models.Annotations
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Index references manifests for various platforms.
 *
 * This structure provides [INDEX_MEDIA_TYPE] mediatype when marshalled to JSON.
 */
@Serializable
class Index(
  override val schemaVersion: Int? = null,
  /** mediaType specifies the type of this document data structure e.g. [INDEX_MEDIA_TYPE] */
  val mediaType: String? = null,
  /** manifests references platform specific manifests. */
  @Serializable(with = CopyOnWriteDescriptorArrayListSerializer::class)
  val manifests: CopyOnWriteArrayList<Descriptor> = CopyOnWriteArrayList(),
  /** annotations contains arbitrary metadata for the image index. */
  val annotations: Annotations? = null,
) : Versioned {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Index) return false
    if (schemaVersion != other.schemaVersion) return false
    if (mediaType != other.mediaType) return false
    if (manifests != other.manifests) return false
    return annotations == other.annotations
  }

  override fun hashCode(): Int {
    var result = schemaVersion.hashCode()
    result = 31 * result + (mediaType?.hashCode() ?: 0)
    result = 31 * result + manifests.hashCode()
    result = 31 * result + (annotations?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Index(schemaVersion=$schemaVersion, mediaType='$mediaType', manifests=$manifests, annotations=$annotations)"
}

/** Generated serializer/deserializer for a CopyOnWriteDescriptorArrayList */
object CopyOnWriteDescriptorArrayListSerializer : KSerializer<CopyOnWriteArrayList<Descriptor>> {
  override val descriptor: SerialDescriptor = ListSerializer(Descriptor.serializer()).descriptor

  override fun serialize(encoder: Encoder, value: CopyOnWriteArrayList<Descriptor>) {
    encoder.encodeSerializableValue(ListSerializer(Descriptor.serializer()), value.toList())
  }

  override fun deserialize(decoder: Decoder): CopyOnWriteArrayList<Descriptor> {
    val list = decoder.decodeSerializableValue(ListSerializer(Descriptor.serializer()))
    return CopyOnWriteArrayList(list)
  }
}
