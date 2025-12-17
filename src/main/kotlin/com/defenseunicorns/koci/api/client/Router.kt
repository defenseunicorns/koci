package com.defenseunicorns.koci.api.client

import com.defenseunicorns.koci.api.models.Descriptor
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.clone
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import java.net.URI

/**
 * Constructs API endpoints for an OCI spec compliant registry.
 *
 * This class handles Url construction for all registry operations including:
 * - Repository listing and tag management
 * - Blob and manifest operations
 * - Upload session management
 * - Cross-repository blob mounting
 *
 * All methods return fully constructed [io.ktor.http.Url] objects ready for use with HTTP clients.
 */
class Router(registryUrl: String) {

  private val base: URLBuilder = URLBuilder().takeFrom(registryUrl).appendPathSegments("v2/")

  /** Returns the base Url for the registry API (v2 endpoint). */
  fun base(): Url {
    return base.build()
  }

  /** Returns the Url for listing all repositories in the registry. */
  fun catalog(): Url {
    return base.clone().appendPathSegments("_catalog").build()
  }

  /**
   * Returns the Url for listing repositories with pagination.
   *
   * @param n Number of repositories to return
   * @param lastRepo Optional repository name to resume listing from
   */
  fun catalog(n: Int, lastRepo: String? = null): Url {
    return base.clone().appendPathSegments("_catalog").paginate(n, lastRepo).build()
  }

  /**
   * Returns the Url for listing all tags in a repository.
   *
   * @param repository Repository name
   */
  fun tags(repository: String): Url {
    return base.clone().appendPathSegments(repository, "tags", "list").build()
  }

  /**
   * Returns the Url for accessing a manifest by reference (tag or digest).
   *
   * @param repository Repository name
   * @param ref Tag or digest reference
   */
  fun manifest(repository: String, ref: String): Url {
    return base.clone().appendPathSegments(repository, "manifests", ref).build()
  }

  /**
   * Returns the Url for accessing a manifest by descriptor.
   *
   * @param repository Repository name
   * @param descriptor Content descriptor containing the digest
   */
  fun manifest(repository: String, descriptor: Descriptor): Url {
    return manifest(repository, descriptor.digest.toString())
  }

  /**
   * Returns the Url for accessing a blob by descriptor.
   *
   * @param repository Repository name
   * @param descriptor Content descriptor containing the digest
   */
  fun blob(repository: String, descriptor: Descriptor): Url {
    return base
      .clone()
      .appendPathSegments(repository, "blobs", descriptor.digest.toString())
      .build()
  }

  /**
   * Returns the Url for initiating a blob upload session.
   *
   * @param repository Repository name
   */
  fun uploads(repository: String): Url {
    // the final "" allows for a trailing /
    return base.clone().appendPathSegments(repository, "blobs", "uploads", "").build()
  }

  /**
   * Returns the Url for mounting a blob from another repository.
   *
   * @param repository Target repository to mount the blob to
   * @param sourceRepository Source repository where the blob exists
   * @param descriptor Blob descriptor to mount
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#mounting-a-blob-from-another-repository">OCI
   *   Distribution Spec: Mounting a Blob</a>
   */
  fun blobMount(repository: String, sourceRepository: String, descriptor: Descriptor): Url {
    return base
      .clone()
      .appendPathSegments(repository, "blobs", "uploads", "")
      .apply {
        parameters.append("mount", descriptor.digest.toString())
        parameters.append("from", sourceRepository)
      }
      .build()
  }

  /**
   * Parses a Location header value into a complete Url.
   *
   * Handles both absolute and relative URLs according to RFC 7231 section 7.1.2. For relative URLs,
   * resolves them against the registry base Url.
   *
   * @param locationHeader Location header value from HTTP response
   * @see <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.2">RFC 7231:
   *   Location</a>
   */
  fun parseUploadLocation(locationHeader: String): Url {
    val uri = URI(locationHeader)
    if (uri.isAbsolute) {
      return URLBuilder().takeFrom(locationHeader).build()
    }

    return URLBuilder().takeFrom(base.build()).apply { encodedPath = locationHeader }.build()
  }

  /**
   * Adds pagination parameters to a Url as specified in the OCI spec.
   *
   * @param n Number of results to return
   * @param last Optional token indicating where to resume listing
   */
  private fun URLBuilder.paginate(n: Int, last: String? = null): URLBuilder = apply {
    parameters.append("n", n.toString())
    last?.let { parameters.append("last", it) }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false
    other as Router
    return base == other.base
  }

  override fun hashCode(): Int {
    return base.hashCode()
  }
}
