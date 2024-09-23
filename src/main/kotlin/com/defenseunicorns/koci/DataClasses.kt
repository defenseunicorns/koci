/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
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
data class Manifest(
    val schemaVersion: Int? = null,
    override val mediaType: String? = null,
    val config: Descriptor,
    val layers: List<Descriptor>,
    val annotations: Annotations? = null,
) : TaggableContent {
    fun sizeOfLayers(): Long {
        return layers.sumOf { it.size }
    }
}

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

data class Reference(
    val registry: String,
    val repository: String,
    val reference: String,
) {
    constructor(registry: Url, repository: String, reference: String) : this(
        registry = registry.toURI().let { uri ->
            if (uri.port != -1) {
                registry.hostWithPort
            } else {
                registry.host // Use host instead of uri.toString() to avoid including the scheme
            }
        },
        repository = repository,
        reference = reference
    )

    companion object {
        //	<--- path --------------------------------------------> |  - Decode `path`
        //	<=== REPOSITORY ===> <--- reference ------------------> |    - Decode `reference`
        //	<=== REPOSITORY ===> @ <=================== digest ===> |      - Valid Form A
        //	<=== REPOSITORY ===> : <!!! TAG !!!> @ <=== digest ===> |      - Valid Form B (tag is dropped)
        //	<=== REPOSITORY ===> : <=== TAG ======================> |      - Valid Form C
        //	<=== REPOSITORY ======================================> |    - Valid Form D
        fun parse(artifact: String): Result<Reference> = runCatching {
            val reg = artifact.substringBefore("/")
            val repoAndRef = artifact.substringAfter("/")

            val (repo, ref) = if (repoAndRef.contains("@")) {
                val ref = Digest(repoAndRef.substringAfter("@")).toString()

                // drop tag if it exists (valid form B)
                repoAndRef.substringBefore("@").substringBefore(":") to ref // valid form A
            } else if (repoAndRef.contains(":")) {
                repoAndRef.substringBefore(":") to repoAndRef.substringAfter(":") // valid form C
            } else {
                repoAndRef to "" // valid form D
            }

            return Result.success(Reference(reg, repo, ref))
        }
    }

    override fun toString(): String {
        return if (reference.contains(":")) {
            "$registry/$repository@$reference" // valid form A
        } else {
           if (reference.isEmpty()) {
               "$registry/$repository" // valid form D
           } else {
               "$registry/$repository:$reference" // valid form C
           }
        }
    }
}

data class UploadStatus(
    val location: String,
    var offset: Long,
    var minChunkSize: Long,
)

interface Target {
    suspend fun exists(descriptor: Descriptor): Result<Boolean>
    suspend fun remove(descriptor: Descriptor): Result<Boolean>
}

sealed interface TaggableContent {
    val mediaType: String?
}
