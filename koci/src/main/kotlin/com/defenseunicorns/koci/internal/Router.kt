/*
 * Copyright 2025-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.clone
import io.ktor.http.encodedPath
import io.ktor.http.parseQueryString
import io.ktor.http.takeFrom
import java.net.URI

/** Builds registry API endpoints from the configured base URL. */
internal class Router(registryURL: String) {
  private companion object {
    private const val V2_PREFIX = "v2/"
  }

  val base: URLBuilder = URLBuilder().takeFrom(registryURL).appendPathSegments(V2_PREFIX)

  /** Registry v2 base URL. */
  fun base(): Url {
    return base.build()
  }

  /** Catalog endpoint, no pagination. */
  fun catalog(): Url {
    return base.clone().appendPathSegments("_catalog").build()
  }

  /** Catalog endpoint with pagination. */
  fun catalog(n: Int, lastRepo: String? = null): Url {
    return base.clone().appendPathSegments("_catalog").paginate(n, lastRepo).build()
  }

  /** Tag-listing endpoint for [repository]. */
  fun tags(repository: String): Url {
    return base.clone().appendPathSegments(repository, "tags", "list").build()
  }

  /** Manifest endpoint addressed by tag or digest [ref]. */
  fun manifest(repository: String, ref: String): Url {
    return base.clone().appendPathSegments(repository, "manifests", ref).build()
  }

  /** Manifest endpoint addressed by descriptor digest, or `null` when the descriptor has none. */
  fun manifest(repository: String, descriptor: Descriptor): Url? {
    val digest = descriptor.digest ?: return null
    return manifest(repository, digest.toString())
  }

  /** Blob endpoint addressed by descriptor digest, or `null` when the descriptor has none. */
  fun blob(repository: String, descriptor: Descriptor): Url? {
    val digest = descriptor.digest ?: return null
    return base.clone().appendPathSegments(repository, "blobs", digest.toString()).build()
  }

  /** Endpoint that opens a new blob upload session. */
  fun uploads(repository: String): Url {
    // Trailing "" preserves the spec-mandated trailing slash on the upload path.
    return base.clone().appendPathSegments(repository, "blobs", "uploads", "").build()
  }

  /**
   * Endpoint that mounts a blob from [sourceRepository] into [repository].
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#mounting-a-blob-from-another-repository">OCI
   *   Distribution Spec: Mounting a Blob</a>
   */
  fun blobMount(repository: String, sourceRepository: String, descriptor: Descriptor): Url? {
    val digest = descriptor.digest ?: return null
    return base
      .clone()
      .appendPathSegments(repository, "blobs", "uploads", "")
      .apply {
        parameters.append("mount", digest.toString())
        parameters.append("from", sourceRepository)
      }
      .build()
  }

  /**
   * Resolves a `Location` header into a full URL, accepting either an absolute URL or a relative
   * path that is resolved against the registry base.
   *
   * @see <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.2">RFC 7231:
   *   Location</a>
   */
  fun parseUploadLocation(locationHeader: String): Url {
    val uri = URI(locationHeader)
    if (uri.isAbsolute) {
      return URLBuilder().takeFrom(locationHeader).build()
    }

    return URLBuilder()
      .apply {
        val baseUrl = base.build()
        protocol = baseUrl.protocol
        host = baseUrl.host
        port = baseUrl.port
        encodedPath = uri.rawPath
        uri.rawQuery?.let { encodedParameters.appendAll(parseQueryString(it, decode = false)) }
      }
      .build()
  }

  /** Adds the OCI-spec pagination parameters `n` and optional `last`. */
  private fun URLBuilder.paginate(n: Int, last: String? = null): URLBuilder = apply {
    parameters.append("n", n.toString())
    last?.let { parameters.append("last", it) }
  }
}
