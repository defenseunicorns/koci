/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
data class CatalogResponse(val repositories: List<String>)

@Serializable
data class TagsResponse(val name: String, val tags: List<String>?)

const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
const val MANIFEST_CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"

@Serializable
data class Manifest(
    val schemaVersion: Int? = null,
    override val mediaType: String? = null,
    val config: Descriptor,
    val layers: List<Descriptor>,
    val annotations: Annotations? = null,
    override val subject: String? = null
) : TaggableContent

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

@Serializable
data class Index(
    val schemaVersion: Int? = null,
    override val mediaType: String? = null,
    @Serializable(with = CopyOnWriteDescriptorArrayListSerializer::class)
    val manifests: CopyOnWriteArrayList<Descriptor> = CopyOnWriteArrayList(),
    val annotations: Annotations? = null,
    override val subject: String? = null
) : TaggableContent

@Serializable
data class LayoutMarker(
    val imageLayoutVersion: String,
)

@Serializable
data class Platform(
    val architecture: String,
    val os: String,
    @SerialName("os.version") val osVersion: String? = null,
    @SerialName("os.features") val osFeatures: List<String>? = null,
    val variant: String? = null,
    val features: List<String>? = null,
)

@Serializable
data class Descriptor(
    val mediaType: String,
    val digest: Digest,
    val size: Long,
    val urls: List<String>? = null,
    val annotations: Annotations? = null,
    val data: String? = null,
    val platform: Platform? = null,
    val artifactType: String? = null,
) {
    companion object {
        fun fromInputStream(
            mediaType: String,
            algorithm: RegisteredAlgorithm = RegisteredAlgorithm.SHA256,
            stream: InputStream,
        ): Descriptor {
            val md = algorithm.hasher()
            var size = 0L
            val buffer = ByteArray(1024)
            stream.use { s ->
                var bytesRead: Int
                while (s.read(buffer).also { bytesRead = it } != -1) {
                    size += bytesRead
                    md.update(buffer, 0, bytesRead)
                }
            }
            return Descriptor(
                mediaType,
                Digest(algorithm, md.digest()),
                size
            )
        }
    }
}

data class UploadStatus(
    val location: String,
    var offset: Long,
    var minChunkSize: Long,
)

sealed interface TaggableContent {
    val mediaType: String?
    val subject: String?
}
