/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.client

import com.defenseunicorns.koci.api.KociResult
import com.defenseunicorns.koci.api.errors.IOError
import com.defenseunicorns.koci.api.models.CatalogResponse
import com.defenseunicorns.koci.auth.SCOPE_REGISTRY_CATALOG
import com.defenseunicorns.koci.auth.appendScopes
import com.defenseunicorns.koci.http.Router
import com.defenseunicorns.koci.http.parseHTTPError
import com.defenseunicorns.koci.models.linkHeaderRegex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * Provides access to OCI spec extensions.
 *
 * Contains methods that extend the core functionality of the OCI Distribution Specification, such
 * as repository catalog listing and pagination.
 *
 * @see <a href="https://github.com/opencontainers/distribution-spec/tree/main/extensions">OCI
 *   Distribution Spec: Extensions</a>
 */
class RegistryExtensions internal constructor(private val client: HttpClient, private val router: Router) {
  /**
   * Lists all repositories in the registry.
   *
   * Performs a GET request to the /v2/_catalog endpoint as specified in the OCI spec. Returns a
   * single page of repository names.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Repositories</a>
   */
  suspend fun catalog(): KociResult<CatalogResponse> {
    return try {
      val response =
        client.get(router.catalog()) { attributes.appendScopes(SCOPE_REGISTRY_CATALOG) }
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      KociResult.ok(Json.decodeFromString(response.body()))
    } catch (e: Exception) {
      KociResult.err(IOError("Failed to fetch catalog: ${e.message}", e))
    }
  }

  /**
   * Lists all repositories in the registry with pagination support.
   *
   * Automatically handles pagination by following Link headers and emitting each page of results as
   * a Flow. This implements the pagination mechanism defined in the OCI spec.
   *
   * @param n Number of repositories to return per page
   * @param lastRepo Optional repository name to resume listing from
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Pagination</a>
   *
   * TODO: distribution is moving to a default max n of 1000
   */
  fun catalog(n: Int, lastRepo: String? = null): Flow<CatalogResponse> = flow {
    var endpoint: Url? = router.catalog(n, lastRepo)

    while (endpoint != null) {
      val response = client.get(endpoint) { attributes.appendScopes(SCOPE_REGISTRY_CATALOG) }

      if (!response.status.isSuccess()) {
        error("HTTP ${response.status.value}: ${response.status.description}")
      }

      // If the header is not present, the client can assume that all results have been received.
      val linkHeader = response.headers[HttpHeaders.Link]

      endpoint =
        linkHeader?.let {
          // TODO: change from regex to a full spec-compliant parser
          // https://datatracker.ietf.org/doc/html/rfc5988#section-5
          val next =
            linkHeaderRegex.find(linkHeader)?.groupValues?.get(1)
              ?: error("$linkHeader does not satisfy $linkHeaderRegex")

          val url = Url(next)
          val nextN =
            url.parameters["n"]?.toInt()
              ?: error("$linkHeader does not contain an 'n' parameter")
          router.catalog(nextN, url.parameters["last"])
        }

      emit(Json.decodeFromString(response.body()))
    }
  }
}
