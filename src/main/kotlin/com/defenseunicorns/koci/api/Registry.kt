/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.AuthConfig
import com.defenseunicorns.koci.api.config.BackOffPolicy
import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.internal.CatalogResponse
import com.defenseunicorns.koci.internal.Layout
import com.defenseunicorns.koci.internal.Router
import com.defenseunicorns.koci.internal.SCOPE_REGISTRY_CATALOG
import com.defenseunicorns.koci.internal.appendScopes
import com.defenseunicorns.koci.internal.succeeded
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/**
 * A reference to a single OCI Distribution-spec registry.
 *
 * Construct via [Koci.registry] — the constructor is internal so that all registries share the
 * parent [Koci]'s HTTP engine and per-registry overrides flow through the DSL. Timeouts are carried
 * on [pull] / [push] and applied per-request.
 *
 * Shares the parent [Koci]'s HTTP client; becomes unusable once that [Koci] has been closed.
 */
public class Registry
internal constructor(
  public val url: String,
  private val auth: AuthConfig,
  private val pull: PullConfig,
  private val push: PushConfig,
  private val backOffPolicy: BackOffPolicy,
  internal val client: HttpClient,
  internal val router: Router,
  internal val store: Layout,
  internal val json: Json,
) {
  /**
   * Returns a [Repository] bound to [name] within this registry (e.g. `"myorg/myimage"`).
   *
   * By default the repository inherits this registry's [pull], [push], and [backOffPolicy]; pass
   * overrides to tune a single repository (for example, raising [pull] concurrency for a hot repo).
   * Auth and timeouts are registry-level because they're installed on the shared HTTP client, so
   * they can't be overridden per-repository.
   */
  public fun repo(name: String): Repository =
    Repository(
      name = name,
      auth = auth,
      pull = pull,
      push = push,
      backOffPolicy = backOffPolicy,
      router = router,
      client = client,
      store = store,
      json = json,
    )

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
  public suspend fun ping(): Boolean = client.get(router.base()).status.isSuccess()

  /**
   * Lists all repositories in the registry.
   *
   * On any failure (HTTP error, parse error, OCI spec error response) returns [emptyList];
   * IOExceptions propagate from the underlying client.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Repositories</a>
   */
  public suspend fun catalog(): List<Repository> {
    val res = client.get(router.catalog()) { attributes.appendScopes(SCOPE_REGISTRY_CATALOG) }
    if (!res.succeeded("registry.catalog")) return emptyList()
    return try {
      res.body<CatalogResponse>().repositories.map { repo(it) }
    } catch (_: SerializationException) {
      // TODO: MOBILE-198 Log
      emptyList()
    }
  }

  /**
   * Lists all repositories in the registry with pagination support.
   *
   * Automatically handles pagination by following Link headers and emitting each page of results as
   * a Flow. This implements the pagination mechanism defined in the OCI spec.
   *
   * @param n Number of repositories to return per page, max of 1000 per page
   * @param lastRepo Optional repository name to resume listing from
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Pagination</a>
   */
  // TODO: MOBILE-217
  public fun catalog(n: Int, lastRepo: String? = null): Flow<List<Repository>> = flow {
    var endpoint: Url? = router.catalog(n.coerceAtMost(MAX_REPOS), lastRepo)

    while (endpoint != null) {
      val response = client.get(endpoint) { attributes.appendScopes(SCOPE_REGISTRY_CATALOG) }

      // If the header is not present, the client can assume that all results have been received.
      val linkHeader = response.headers[HttpHeaders.Link]

      // TODO: MOBILE-214
      endpoint =
        linkHeader?.let {
          // TODO: change from regex to a full spec-compliant parser
          // https://datatracker.ietf.org/doc/html/rfc5988#section-5
          val regex = Regex("<(.+)>;\\s+rel=\"next\"")

          // TODO: MOBILE-198 Log the regex error
          val next = regex.find(linkHeader)?.groups?.get(1)?.value ?: return@flow

          val url = Url(next)

          // TODO: MOBILE-198 Log failure
          val nextN = url.parameters["n"]?.toInt() ?: return@flow
          router.catalog(nextN, url.parameters["last"])
        }

      val catalog = response.body<CatalogResponse>()
      val repos = catalog.repositories.map { repo(it) }
      emit(repos)
    }
  }

  /**
   * Lists all repositories with their tags.
   *
   * This is a convenience method that combines the catalog and tags endpoints to provide a
   * flattened list of all repositories and their tags.
   *
   * This is **NOT** part of the OCI spec but simplifies common workflows.
   *
   * The amount of actions taken at a single time concurrently is based on [PullConfig.concurrency].
   *
   * @param n Number of repositories to return per page in the catalog request
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  public fun list(n: Int = MAX_REPOS): Flow<List<String>> =
    catalog(n)
      .flatMapMerge(concurrency = pull.concurrency) { repositories -> repositories.asFlow() }
      .map { repo -> repo.tags() }

  override fun toString(): String = "Registry(url=$url)"

  private companion object {
    private const val MAX_REPOS = 1000
  }
}
