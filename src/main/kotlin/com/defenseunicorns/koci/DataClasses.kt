/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.ContentType.Application
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Response structure for repository catalog requests.
 *
 * Contains a list of repository names available in the registry.
 *
 * @property repositories List of repository names in the registry
 */
@Serializable
data class CatalogResponse(val repositories: List<String>)

/**
 * Response structure for repository tags list requests.
 *
 * Contains the repository name and its associated tags.
 *
 * @property name Repository name
 * @property tags List of tags associated with the repository, may be null if no tags exist
 */
@Serializable
data class TagsResponse(val name: String, val tags: List<String>?)

/**
 * MANIFEST_MEDIA_TYPE specifies the media type for an image manifest.
 */
const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"

/**
 * MANIFEST_CONFIG_MEDIA_TYPE specifies the media type for the image configuration.
 */
const val MANIFEST_CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"

/**
 * INDEX_MEDIA_TYPE specifies the media type for an image index.
 */
const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"

/**
 * IMAGE_LAYOUT_FILE is the file name containing [LayoutMarker] in an OCI Image [Layout]
 */
const val IMAGE_LAYOUT_FILE = "oci-layout"

/**
 * IMAGE_INDEX_FILE is the file name of the entry point for references and descriptors in an OCI Image [Layout]
 */
const val IMAGE_INDEX_FILE = "index.json"

/**
 * IMAGE_BLOBS_DIR is the directory name containing content addressable blobs in an OCI Image [Layout]
 */
const val IMAGE_BLOBS_DIR = "blobs"

/**
 *  Manifest provides [MANIFEST_MEDIA_TYPE] mediatype structure when marshalled to JSON.
 */
@Serializable
data class Manifest(
    /**
     * schemaVersion is the image manifest schema that this image follows
     */
    override val schemaVersion: Int? = null,
    /**
     * mediaType specifies the type of this document data structure e.g. [MANIFEST_MEDIA_TYPE]
     */
    val mediaType: String? = null,
    /**
     * config references a configuration object for a container, by digest.
     *
     * The referenced configuration object is a JSON blob that the runtime uses to set up the container.
     */
    val config: Descriptor,
    /**
     * layers is an indexed list of layers referenced by the manifest.
     */
    val layers: List<Descriptor>,
    /**
     * annotations contains arbitrary metadata for the image manifest.
     */
    val annotations: Annotations? = null,
) : Versioned

/**
 * Generated serializer/deserializer for a CopyOnWriteDescriptorArrayList
 */
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

/**
 * Index references manifests for various platforms.
 *
 *  This structure provides [INDEX_MEDIA_TYPE] mediatype when marshalled to JSON.
 */
@Serializable
data class Index(
    override val schemaVersion: Int? = null,
    /**
     * mediaType specifies the type of this document data structure e.g. [INDEX_MEDIA_TYPE]
     */
    val mediaType: String? = null,
    /**
     * manifests references platform specific manifests.
     */
    @Serializable(with = CopyOnWriteDescriptorArrayListSerializer::class)
    val manifests: CopyOnWriteArrayList<Descriptor> = CopyOnWriteArrayList(),
    /**
     * annotations contains arbitrary metadata for the image index.
     */
    val annotations: Annotations? = null,
) : Versioned

/**
 * LayoutMarker is the structure in the "oci-layout" file, found in the root of an OCI [Layout] directory
 */
@Serializable
data class LayoutMarker(
    val imageLayoutVersion: String,
)

/**
 * Platform describes the platform which the image in the manifest runs on.
 */
@Serializable
data class Platform(
    /**
     * architecture field specifies the CPU architecture, for example
     * `amd64` or `ppc64le`.
     */
    val architecture: String,
    /**
     * os specifies the operating system, for example `linux` or `windows`.
     */
    val os: String,
    /**
     * osVersion is an optional field specifying the operating system
     * version, for example on Windows `10.0.14393.1066`.
     */
    @SerialName("os.version") val osVersion: String? = null,
    /**
     * osFeatures is an optional field specifying an array of strings,
     * each listing a required OS feature (for example on Windows `win32k`).
     */
    @SerialName("os.features") val osFeatures: List<String>? = null,
    /**
     * variant is an optional field specifying a variant of the CPU, for
     * example `v7` to specify ARMv7 when architecture is `arm`.
     */
    val variant: String? = null,
)

/**
 * Descriptor describes the disposition of targeted content.
 *
 * This structure provides `application/vnd.oci.descriptor.v1+json` mediatype
 * when marshalled to JSON.
 */
@Serializable
data class Descriptor(
    /**
     * mediaType is the media type of the object this schema refers to.
     */
    val mediaType: String,
    /**
     * digest is the digest of the targeted content.
     */
    val digest: Digest,
    /**
     * size specifies the size in bytes of the blob.
     */
    val size: Long,
    /**
     * urls specifies a list of URLs from which this object MAY be downloaded
     */
    val urls: List<String>? = null,
    /**
     * annotations contains arbitrary metadata relating to the targeted content.
     */
    val annotations: Annotations? = null,
    /**
     * data is an embedding of the targeted content. This is encoded as a base64
     * string when marshalled to JSON (automatically, by encoding/json). If
     * present, data can be used directly to avoid fetching the targeted content.
     */
    val data: String? = null,
    /**
     * platform describes the platform which the image in the manifest runs on.
     *
     * This should only be used when referring to a manifest.
     */
    val platform: Platform? = null,
) {
    companion object {
        /**
         * fromInputStream returns a [Descriptor], given the content and media type.
         *
         * if no media type is specified, "application/octet-stream" will be used
         */
        fun fromInputStream(
            mediaType: String = Application.OctetStream.toString(),
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

/**
 * UploadStatus tracks the server-side state of an upload
 */
data class UploadStatus(
    val location: String,
    var offset: Long,
    var minChunkSize: Long,
)

/**
 * Common interface for versioned OCI content types.
 *
 * Both Manifest and Index implement this interface to provide a common
 * way to access the schema version.
 *
 * @property schemaVersion The schema version of the content
 */
sealed interface Versioned {
    val schemaVersion: Int?
}
