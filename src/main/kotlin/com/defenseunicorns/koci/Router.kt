/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import java.net.URI

/**
 * Adds pagination parameters to a URL as specified in the OCI spec.
 *
 * @param n Number of results to return
 * @param last Optional token indicating where to resume listing
 */
private fun URLBuilder.paginate(n: Int, last: String? = null): URLBuilder = apply {
  parameters.append("n", n.toString())
  last?.let { parameters.append("last", it) }
}

/**
 * Constructs API endpoints for an OCI spec compliant registry.
 *
 * This class handles URL construction for all registry operations including:
 * - Repository listing and tag management
 * - Blob and manifest operations
 * - Upload session management
 * - Cross-repository blob mounting
 *
 * All methods return fully constructed [Url] objects ready for use with HTTP clients.
 */
class Router(registryURL: String) {
  companion object {
    private const val V2_PREFIX = "v2/"
  }

  private val base: URLBuilder = URLBuilder().takeFrom(registryURL).appendPathSegments(V2_PREFIX)

  /** Returns the base URL for the registry API (v2 endpoint). */
  fun base(): Url {
    return base.build()
  }

  /** Returns the URL for listing all repositories in the registry. */
  fun catalog(): Url {
    return base.clone().appendPathSegments("_catalog").build()
  }

  /**
   * Returns the URL for listing repositories with pagination.
   *
   * @param n Number of repositories to return
   * @param lastRepo Optional repository name to resume listing from
   */
  fun catalog(n: Int, lastRepo: String? = null): Url {
    return base.clone().appendPathSegments("_catalog").paginate(n, lastRepo).build()
  }

  /**
   * Returns the URL for listing all tags in a repository.
   *
   * @param repository Repository name
   */
  fun tags(repository: String): Url {
    return base.clone().appendPathSegments(repository, "tags", "list").build()
  }

  /**
   * Returns the URL for accessing a manifest by reference (tag or digest).
   *
   * @param repository Repository name
   * @param ref Tag or digest reference
   */
  fun manifest(repository: String, ref: String): Url {
    return base.clone().appendPathSegments(repository, "manifests", ref).build()
  }

  /**
   * Returns the URL for accessing a manifest by descriptor.
   *
   * @param repository Repository name
   * @param descriptor Content descriptor containing the digest
   */
  fun manifest(repository: String, descriptor: Descriptor): Url {
    return manifest(repository, descriptor.digest.toString())
  }

  /**
   * Returns the URL for accessing a blob by descriptor.
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
   * Returns the URL for initiating a blob upload session.
   *
   * @param repository Repository name
   */
  fun uploads(repository: String): Url {
    // the final "" allows for a trailing /
    return base.clone().appendPathSegments(repository, "blobs", "uploads", "").build()
  }

  /**
   * Returns the URL for mounting a blob from another repository.
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
   * Parses a Location header value into a complete URL.
   *
   * Handles both absolute and relative URLs according to RFC 7231 section 7.1.2. For relative URLs,
   * resolves them against the registry base URL.
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
}
