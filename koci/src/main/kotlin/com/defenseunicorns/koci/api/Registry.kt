/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.internal.CatalogResponse
import com.defenseunicorns.koci.internal.HttpWrapper
import com.defenseunicorns.koci.internal.KociLogger
import com.defenseunicorns.koci.internal.Regex.linkRegex
import com.defenseunicorns.koci.internal.Router
import com.defenseunicorns.koci.internal.SCOPE_REGISTRY_CATALOG
import com.defenseunicorns.koci.internal.appendScopes
import io.ktor.client.call.body
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.hostWithPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * A handle to a single OCI Distribution-compliant registry. Obtained via [Koci.registry].
 *
 * Becomes unusable once its parent [Koci] is closed.
 */
public class Registry
internal constructor(
  public val url: String,
  private val push: PushConfig,
  private val pull: PullConfig,
  internal val httpWrapper: HttpWrapper,
  internal val router: Router,
  internal val store: Layout,
  internal val json: Json,
  private val logger: KociLogger,
) {
  public val name: String = Url(url).hostWithPort

  /** Returns a [Repository] bound to [name] within this registry, e.g. `"myorg/myimage"`. */
  public fun repo(name: String): Repository =
    Repository(
      name = name,
      push = push,
      pull = pull,
      router = router,
      httpWrapper = httpWrapper,
      store = store,
      json = json,
      logger = logger,
    )

  /**
   * Returns `true` if the registry implements the v2 API and accepts requests from this client. Any
   * transport failure surfaces as `false`; no exception escapes.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support">OCI
   *   Distribution Spec: API Version Check</a>
   */
  public suspend fun ping(): Boolean {
    val outcome =
      httpWrapper.call(
        operation = "registry.ping",
        buildRequest = { url(router.base()) },
        onSuccess = { true },
      )
    return outcome ?: false
  }

  /**
   * Emits one page of repositories at a time, following `Link` headers until the registry reports
   * no more pages.
   *
   * @param n Repositories per page. Capped at 1000.
   * @param lastRepo If set, resume listing after this repository name (exclusive).
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-repositories">OCI
   *   Distribution Spec: Listing Repositories</a>
   */
  // TODO: #676
  public fun catalog(n: Int = MAX_REPOS, lastRepo: String? = null): Flow<List<Repository>> = flow {
    val endpoint: Url = router.catalog(n.coerceAtMost(MAX_REPOS), lastRepo)

    suspend fun grabPage(pageUrl: Url?): Url? {
      if (pageUrl == null) {
        return null
      }

      val outcome =
        httpWrapper.call(
          operation = "registry.catalog",
          buildRequest = {
            url(pageUrl)
            attributes.appendScopes(SCOPE_REGISTRY_CATALOG)
          },
          onSuccess = { res ->
            val next = parseNextLink(res.headers[HttpHeaders.Link])
            val repos = res.body<CatalogResponse>().repositories.map { repo(it) }
            repos to next
          },
        )

      val page = outcome ?: return null
      emit(page.first)

      return grabPage(page.second)
    }

    grabPage(endpoint)
  }

  private fun parseNextLink(linkHeader: String?): Url? {
    if (linkHeader == null) {
      return null
    }
    val next = linkRegex.find(linkHeader)?.groups?.get(1)?.value ?: return null
    val url = Url(next)
    val nextN = url.parameters["n"]?.toIntOrNull() ?: return null
    return router.catalog(nextN, url.parameters["last"])
  }

  override fun toString(): String = "Registry(url=$url)"

  private companion object {
    private const val MAX_REPOS = 1000
  }
}
