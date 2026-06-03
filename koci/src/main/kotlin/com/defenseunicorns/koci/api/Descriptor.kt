/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import io.ktor.http.ContentType.Application
import java.io.InputStream
import kotlinx.serialization.Serializable
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source

/** Identifies an OCI blob, manifest, or index by media type, content digest, and size. */
@Serializable
public class Descriptor(
  /** Media type of the referenced content. */
  public val mediaType: String,
  /**
   * Content digest. Nullable so descriptors deserialized from malformed wire data still parse;
   * downstream consumers skip null-digest entries.
   */
  public val digest: Digest?,
  /** Size in bytes of the referenced content. */
  public val size: Long,
  /** Optional URLs from which the content may be downloaded. */
  public val urls: List<String>? = null,
  /** Arbitrary key/value metadata. */
  public val annotations: Annotations? = null,
  /** Inline content, base64-encoded when present in JSON. Lets consumers skip a fetch when set. */
  public val data: String? = null,
  /** Target platform. Only meaningful when the descriptor refers to a manifest. */
  public val platform: Platform? = null,
  /** Artifact media type when this descriptor refers to an artifact manifest. */
  public val artifactType: String? = null,
) {
  public fun copy(
    mediaType: String = this.mediaType,
    digest: Digest? = this.digest,
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
    result = 31 * result + (digest?.hashCode() ?: 0)
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
     * Reads [stream] to compute its digest and size, returning a [Descriptor]. Defaults to
     * `application/octet-stream` and SHA-256. The stream is fully consumed and closed.
     */
    public fun fromInputStream(
      stream: InputStream,
      mediaType: String = Application.OctetStream.toString(),
      algorithm: RegisteredAlgorithm = RegisteredAlgorithm.SHA256,
    ): Descriptor {
      val hashingSource =
        when (algorithm) {
          RegisteredAlgorithm.SHA256 -> HashingSource.sha256(stream.source())
          RegisteredAlgorithm.SHA512 -> HashingSource.sha512(stream.source())
        }
      val size = hashingSource.buffer().use { it.readAll(blackholeSink()) }
      return Descriptor(mediaType, Digest(algorithm, hashingSource.hash.hex()), size)
    }
  }
}
