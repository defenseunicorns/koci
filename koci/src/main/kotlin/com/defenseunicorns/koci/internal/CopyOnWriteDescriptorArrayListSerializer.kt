/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Serializer/deserializer for a [CopyOnWriteArrayList] of [Descriptor]. */
internal object CopyOnWriteDescriptorArrayListSerializer :
  KSerializer<CopyOnWriteArrayList<Descriptor>> {
  override val descriptor: SerialDescriptor = ListSerializer(Descriptor.serializer()).descriptor

  override fun serialize(encoder: Encoder, value: CopyOnWriteArrayList<Descriptor>) {
    encoder.encodeSerializableValue(ListSerializer(Descriptor.serializer()), value.toList())
  }

  override fun deserialize(decoder: Decoder): CopyOnWriteArrayList<Descriptor> {
    val list = decoder.decodeSerializableValue(ListSerializer(Descriptor.serializer()))
    return CopyOnWriteArrayList(list)
  }
}
