package com.defenseunicorns.koci.models.content

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
data class Descriptor(
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
  companion object {
    /**
     * fromInputStream returns a [Descriptor], given the content and media type.
     *
     * if no media type is specified, "application/octet-stream" will be used
     */
    fun fromInputStream(
      stream: InputStream,
      mediaType: String = ContentType.Application.OctetStream.toString(),
      algorithm: RegisteredAlgorithm = RegisteredAlgorithm.SHA256,
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
      return Descriptor(mediaType, Digest(algorithm, md.digest()), size)
    }
  }
}
