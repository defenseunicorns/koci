/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.client

import com.defenseunicorns.koci.auth.OCIAuthPlugin
import com.defenseunicorns.koci.auth.SCOPE_REGISTRY_CATALOG
import com.defenseunicorns.koci.auth.appendScopes
import com.defenseunicorns.koci.http.Router
import com.defenseunicorns.koci.models.content.Platform
import com.defenseunicorns.koci.models.errors.FailureResponse
import com.defenseunicorns.koci.models.errors.OCIError
import com.defenseunicorns.koci.models.errors.OCIResult
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.http.isSuccess
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Main entry point for interacting with OCI spec compliant registries.
 *
 * Provides access to registry-level operations such as:
 * - API version check (ping)
 * - Repository catalog listing
 * - Repository access via the [repo] extension function
 *
 * Configures HTTP client with appropriate headers and plugins for OCI registry communication.
 *
 * Example usage:
 * ```kotlin
 * val registry = Registry("https://ghcr.io")
 * val pingResult = registry.ping()
 * val repository = registry.repo("myorg/myrepo")
 * ```
 */
class Registry(registryURL: String, var client: HttpClient = HttpClient(CIO)) {
  val router = Router(registryURL)
  val extensions = Extensions()

  init {
    val timeoutPlugin = client.pluginOrNull(HttpTimeout)
    val ociAuthPlugin = client.pluginOrNull(OCIAuthPlugin)
    client =
      client.config {
        headers {
          // https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support
          append("Docker-Distribution-API-Version", "registry/2.0")
        }

        if (timeoutPlugin == null) {
          install(HttpTimeout) { this.requestTimeoutMillis = 10.minutes.inWholeMilliseconds }
        }
        if (ociAuthPlugin == null) {
          install(OCIAuthPlugin)
        }

        expectSuccess = false // Let responses through, handle errors explicitly
      }
  }

  /**
   * Checks API version compatibility with the registry.
   *
   * Performs a GET request to the /v2/ endpoint as specified in the OCI Distribution Specification.
   * A successful response indicates the registry implements the API and the client is authorized to
   * access it.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support">OCI
   *   Distribution Spec: API Version Check</a>
   */
  suspend fun ping(): OCIResult<Boolean> {
    return try {
      val response = client.get(router.base())
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      OCIResult.ok(true)
    } catch (e: Exception) {
      OCIResult.err(OCIError.IOError("Network error: ${e.message}", e))
    }
  }

  /**
   * Provides access to OCI spec extensions.
   *
   * Contains methods that extend the core functionality of the OCI Distribution Specification, such
   * as repository catalog listing and pagination.
   *
   * @see <a href="https://github.com/opencontainers/distribution-spec/tree/main/extensions">OCI
   *   Distribution Spec: Extensions</a>
   */
  inner class Extensions {
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
    suspend fun catalog(): OCIResult<CatalogResponse> {
      return try {
        val response =
          client.get(router.catalog()) { attributes.appendScopes(SCOPE_REGISTRY_CATALOG) }
        if (!response.status.isSuccess()) {
          return parseHTTPError(response)
        }
        OCIResult.ok(Json.decodeFromString(response.body()))
      } catch (e: Exception) {
        OCIResult.err(OCIError.IOError("Failed to fetch catalog: ${e.message}", e))
      }
    }

    /**
     * Lists all repositories in the registry with pagination support.
     *
     * Automatically handles pagination by following Link headers and emitting each page of results
     * as a Flow. This implements the pagination mechanism defined in the OCI spec.
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
          throw IllegalStateException("HTTP ${response.status.value}: ${response.status.description}")
        }

        // If the header is not present, the client can assume that all results have been received.
        val linkHeader = response.headers[HttpHeaders.Link]

        endpoint =
          linkHeader?.let {
            // TODO: change from regex to a full spec-compliant parser
            // https://datatracker.ietf.org/doc/html/rfc5988#section-5
            val regex = Regex("<(.+)>;\\s+rel=\"next\"")
            val next =
              regex.find(linkHeader)?.groupValues?.get(1)
                ?: throw IllegalStateException("$linkHeader does not satisfy $regex")

            val url = Url(next)
            val nextN =
              url.parameters["n"]?.toInt()
                ?: throw IllegalStateException("$linkHeader does not contain an 'n' parameter")
            router.catalog(nextN, url.parameters["last"])
          }

        emit(Json.decodeFromString(response.body()))
      }
    }

    /**
     * Lists all repositories with their tags.
     *
     * This is a convenience method that combines the catalog and tags endpoints to provide a
     * flattened list of all repositories and their tags. This is not part of the OCI spec but
     * simplifies common workflows.
     *
     * Emits OCIResult<TagsResponse> for each repository. Errors are emitted as OCIResult.Err.
     *
     * @param n Number of repositories to return per page in the catalog request
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun list(n: Int = 1000): Flow<OCIResult<TagsResponse>> =
      catalog(n)
        .flatMapConcat { catalogResponse -> catalogResponse.repositories.asFlow() }
        .map { repo -> tags(repo) }
  }
}

/**
 * Creates a Repository instance for interacting with a specific repository.
 *
 * This extension function provides access to repository-specific operations such as tag listing,
 * content pushing/pulling, and manifest operations.
 *
 * @param name Repository name (e.g., "library/ubuntu")
 */
fun Registry.repo(name: String) = Repository(client, router, name)

/**
 * Lists all tags in a repository.
 *
 * Performs a GET request to the /v2/{name}/tags/list endpoint as specified in the OCI spec.
 *
 * @param repository Repository name to list tags for
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
 *   Distribution Spec: Listing Image Tags</a>
 */
suspend fun Registry.tags(repository: String) = repo(repository).tags()

/**
 * Resolves a tag to a content descriptor.
 *
 * Performs a HEAD or GET request to the /v2/{name}/manifests/{reference} endpoint as specified in
 * the OCI spec. For multi-platform images, the platformResolver can be used to select a specific
 * platform.
 *
 * @param repository Repository name
 * @param tag Tag to resolve
 * @param platformResolver Optional function to select platform from index manifest
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pull">OCI
 *   Distribution Spec: Existing Manifests</a>
 */
suspend fun Registry.resolve(
  repository: String,
  tag: String,
  platformResolver: ((Platform) -> Boolean)? = null,
) = repo(repository).resolve(tag, platformResolver)

/**
 * Pulls content by tag and stores it in the provided layout.
 *
 * Resolves the tag to a descriptor, then pulls the manifest and all referenced content. For
 * multi-platform images, the platformResolver can be used to select a specific platform.
 *
 * @param repository Repository name
 * @param tag Tag to pull
 * @param storage Layout to store content in
 * @param platformResolver Optional function to select platform from index manifest
 */
fun Registry.pull(
  repository: String,
  tag: String,
  storage: Layout,
  platformResolver: ((Platform) -> Boolean)? = null,
) = repo(repository).pull(tag, storage, platformResolver)

/**
 * Parses an HTTP error response into an OCIError.
 *
 * Attempts to parse JSON error responses from OCI registries according to the OCI Distribution
 * Specification error format. Falls back to generic HTTP errors if parsing fails.
 *
 * @param response The HTTP response with an error status code
 * @return OCIResult.Err containing the parsed error
 */
suspend fun <T> parseHTTPError(response: HttpResponse): OCIResult<T> {
  // Try to parse OCI-compliant error response
  if (response.contentType() == ContentType.Application.Json) {
    try {
      val failureResponse: FailureResponse = response.body()
      failureResponse.status = response.status
      return OCIResult.err(OCIError.FromResponse(failureResponse))
    } catch (_: NoTransformationFoundException) {
      // Fall through to generic error
    }
  }

  // Generic HTTP error
  return OCIResult.err(
    OCIError.HTTPError(response.status.value, response.status.description)
  )
}

/**
 * Response structure for repository catalog requests.
 *
 * Contains a list of repository names available in the registry.
 *
 * @property repositories List of repository names in the registry
 */
@Serializable data class CatalogResponse(val repositories: List<String>)

/**
 * Response structure for repository tags list requests.
 *
 * Contains the repository name and its associated tags.
 *
 * @property name Repository name
 * @property tags List of tags associated with the repository, may be null if no tags exist
 */
@Serializable data class TagsResponse(val name: String, val tags: List<String>?)
