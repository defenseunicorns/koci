/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import io.ktor.http.ContentType.Application
import java.io.InputStream
import kotlinx.serialization.Serializable

/**
 * Descriptor describes the disposition of targeted content.
 *
 * This structure provides `application/vnd.oci.descriptor.v1+json` mediatype when marshalled to
 * JSON.
 */
@Serializable
public class Descriptor(
  /** mediaType is the media type of the object this schema refers to. */
  public val mediaType: String,
  /** digest is the digest of the targeted content. */
  public val digest: Digest,
  /** size specifies the size in bytes of the blob. */
  public val size: Long,
  /** urls specifies a list of URLs from which this object MAY be downloaded */
  public val urls: List<String>? = null,
  /** annotations contains arbitrary metadata relating to the targeted content. */
  public val annotations: Annotations? = null,
  /**
   * data is an embedding of the targeted content. This is encoded as a base64 string when
   * marshalled to JSON (automatically, by encoding/json). If present, data can be used directly to
   * avoid fetching the targeted content.
   */
  public val data: String? = null,
  /**
   * platform describes the platform which the image in the manifest runs on.
   *
   * This should only be used when referring to a manifest.
   */
  public val platform: Platform? = null,

  /**
   * contains the type of an artifact when the descriptor points to an artifact when the descriptor
   * references an image manifest
   */
  public val artifactType: String? = null,
) {
  public fun copy(
    mediaType: String = this.mediaType,
    digest: Digest = this.digest,
    size: Long = this.size,
    urls: List<String>? = this.urls,
    annotations: Annotations? = this.annotations,
    data: String? = this.data,
    platform: Platform? = this.platform,
  ): Descriptor = Descriptor(mediaType, digest, size, urls, annotations, data, platform)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Descriptor) return false
    return mediaType == other.mediaType &&
      digest == other.digest &&
      size == other.size &&
      urls == other.urls &&
      annotations == other.annotations &&
      data == other.data &&
      platform == other.platform
  }

  override fun hashCode(): Int {
    var result = mediaType.hashCode()
    result = 31 * result + digest.hashCode()
    result = 31 * result + size.hashCode()
    result = 31 * result + (urls?.hashCode() ?: 0)
    result = 31 * result + (annotations?.hashCode() ?: 0)
    result = 31 * result + (data?.hashCode() ?: 0)
    result = 31 * result + (platform?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Descriptor(mediaType=$mediaType, digest=$digest, size=$size, urls=$urls, " +
      "annotations=$annotations, data=$data, platform=$platform)"

  public companion object {
    /**
     * fromInputStream returns a [Descriptor], given the content and media type.
     *
     * if no media type is specified, "application/octet-stream" will be used
     */
    public fun fromInputStream(
      stream: InputStream,
      mediaType: String = Application.OctetStream.toString(),
      algorithm: RegisteredAlgorithm = RegisteredAlgorithm.SHA256,
    ): Descriptor {
      val md = algorithm.hasher()
      var size = 0L
      val buffer = ByteArray(READ_BUFFER_SIZE)
      stream.use { s ->
        var bytesRead: Int
        while (s.read(buffer).also { bytesRead = it } != -1) {
          size += bytesRead
          md.update(buffer, 0, bytesRead)
        }
      }
      return Descriptor(mediaType, Digest(algorithm, md.digest()), size)
    }

    private const val READ_BUFFER_SIZE = 1024
  }
}
