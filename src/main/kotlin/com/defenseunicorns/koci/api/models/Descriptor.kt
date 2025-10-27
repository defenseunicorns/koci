/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import com.defenseunicorns.koci.models.Annotations
import io.ktor.http.ContentType
import java.io.InputStream
import kotlinx.serialization.Serializable

/**
 * Descriptor describes the disposition of targeted content.
 *
 * This structure provides `application/vnd.oci.descriptor.v1+json` mediatype when marshalled to
 * JSON.
 */
@Serializable
class Descriptor(
  /** mediaType is the media type of the object this schema refers to. */
  val mediaType: String,
  /** digest is the digest of the targeted content. */
  val digest: Digest,
  /** size specifies the size in bytes of the blob. */
  val size: Long,
  /** urls specifies a list of URLs from which this object MAY be downloaded */
  val urls: List<String>? = null,
  /** annotations contains arbitrary metadata relating to the targeted content. */
  val annotations: Annotations? = null,
  /**
   * data is an embedding of the targeted content. This is encoded as a base64 string when
   * marshalled to JSON (automatically, by encoding/json). If present, data can be used directly to
   * avoid fetching the targeted content.
   */
  val data: String? = null,
  /**
   * platform describes the platform which the image in the manifest runs on.
   *
   * This should only be used when referring to a manifest.
   */
  val platform: Platform? = null,
) {

  fun copy(
    mediaType: String = this.mediaType,
    digest: Digest = this.digest,
    size: Long = this.size,
    urls: List<String>? = this.urls,
    annotations: Annotations? = this.annotations,
    data: String? = this.data,
    platform: Platform? = this.platform,
  ) =
    Descriptor(
      mediaType = mediaType,
      digest = digest,
      size = size,
      urls = urls,
      annotations = annotations,
      data = data,
      platform = platform,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Descriptor) return false
    if (mediaType != other.mediaType) return false
    if (digest != other.digest) return false
    if (size != other.size) return false
    if (urls != other.urls) return false
    if (annotations != other.annotations) return false
    return data == other.data
  }

  override fun hashCode(): Int {
    var result = mediaType.hashCode()
    result = 31 * result + digest.hashCode()
    result = 31 * result + size.hashCode()
    result = 31 * result + (urls?.hashCode() ?: 0)
    result = 31 * result + (annotations?.hashCode() ?: 0)
    result = 31 * result + (data?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Descriptor(mediaType='$mediaType', digest=$digest, size=$size, urls=$urls, annotations=$annotations, data=$data)"

  companion object {
    private const val BUFFER_SIZE = 1024

    /**
     * fromInputStream returns a [Descriptor], given the content and media type.
     *
     * if no media type is specified, "application/octet-stream" will be used
     */
    fun fromInputStream(
      stream: InputStream,
      mediaType: String = ContentType.Application.OctetStream.toString(),
      algorithm: RegisteredAlgorithm = RegisteredAlgorithm.SHA256,
    ): Descriptor? {
      val md = algorithm.hasher()
      var size = 0L
      val buffer = ByteArray(BUFFER_SIZE)
      stream.use { s ->
        var bytesRead: Int
        while (s.read(buffer).also { bytesRead = it } != -1) {
          size += bytesRead
          md.update(buffer, 0, bytesRead)
        }
      }

      val digest = Digest.create(algorithm, md.digest())

      return digest?.let { Descriptor(mediaType, digest, size) }
    }
  }
}
