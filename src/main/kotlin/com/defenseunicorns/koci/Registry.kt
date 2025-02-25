/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

/**
 * Registry is the main entrypoint for koci's operations.
 *
 *     Registry("https://ghcr.io", httpClient)
 */
class Registry(
    registryURL: String,
    var client: HttpClient = HttpClient(CIO),
) {
    val router = Router(registryURL)
    val extensions = Extensions()

    init {
        client = client.config {
            headers {
                // https://distribution.github.io/distribution/spec/api/#api-version-check
                append("Docker-Distribution-API-Version", "registry/2.0")
            }

            HttpResponseValidator {
                handleResponseExceptionWithRequest { exception, _ ->
                    val clientException =
                        exception as? ClientRequestException ?: return@handleResponseExceptionWithRequest
                    attemptThrow4XX(clientException.response)
                    return@handleResponseExceptionWithRequest
                }
            }

            expectSuccess = true
        }

        if (client.pluginOrNull(OCIAuthPlugin) == null) {
            client = client.config {
                install(OCIAuthPlugin)
            }
        }
    }

    /**
     * [GET /v2/](https://distribution.github.io/distribution/spec/api/#api-version-check)
     */
    suspend fun ping(): Result<Boolean> = runCatching {
        client.get(router.base()).status.isSuccess()
    }

    /**
     * [Distribution Extensions API](https://github.com/opencontainers/distribution-spec/tree/main/extensions)
     */
    inner class Extensions {
        /**
         * [GET /v2/_catalog](https://distribution.github.io/distribution/spec/api/#listing-repositories)
         */
        suspend fun catalog(): Result<CatalogResponse> = runCatching {
            val res = client.get(router.catalog()) {
                attributes.appendScopes(SCOPE_REGISTRY_CATALOG)
            }
            Json.decodeFromString(res.body())
        }

        /**
         * Same as [catalog] but [paginates](https://distribution.github.io/distribution/spec/api/#pagination) using [Flow]
         *
         * TODO: distribution is moving to a default max n of 1000
         */
        fun catalog(n: Int, lastRepo: String? = null): Flow<CatalogResponse> = flow {
            var endpoint: Url? = router.catalog(n, lastRepo)

            while (endpoint != null) {
                val response = client.get(endpoint) {
                    attributes.appendScopes(SCOPE_REGISTRY_CATALOG)
                }

                // If the header is not present, the client can assume that all results have been received.
                val linkHeader = response.headers[HttpHeaders.Link]

                endpoint = linkHeader?.let {
                    // TODO: change from regex to a full spec-compliant parser
                    // https://datatracker.ietf.org/doc/html/rfc5988#section-5
                    val regex = Regex("<(.+)>;\\s+rel=\"next\"")
                    val next = checkNotNull(regex.find(linkHeader)?.groupValues?.get(1)) {
                        "$linkHeader does not satisfy $regex"
                    }

                    val url = Url(next)
                    val nextN = checkNotNull(url.parameters["n"]?.toInt()) {
                        "$linkHeader does not contain an 'n' parameter"
                    }
                    router.catalog(nextN, url.parameters["last"])
                }

                emit(Json.decodeFromString(response.body()))
            }
        }

        /**
         * _Not in the spec_, this combines the [catalog] and the [tags] endpoints to return a flattened
         * list of all repos in the registry alongside their tags
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun list(n: Int = 1000): Flow<TagsResponse> = catalog(n)
            .flatMapConcat { catalogResponse ->
                catalogResponse.repositories.asFlow()
            }
            .map { repo ->
                tags(repo).getOrThrow()
            }
    }
}

/**
 * Connect to a repository.
 */
fun Registry.repo(name: String) = Repository(client, router, name)

/**
 * [GET /v2/<name>/tags/list](https://distribution.github.io/distribution/spec/api/#listing-image-tags)
 */
suspend fun Registry.tags(repository: String) = repo(repository).tags()

/**
 * [HEAD|GET /v2/<name>/manifests/<reference>](https://distribution.github.io/distribution/spec/api/#existing-manifests)
 */
suspend fun Registry.resolve(repository: String, tag: String, platformResolver: ((Platform) -> Boolean)? = null) =
    repo(repository).resolve(tag, platformResolver)

/**
 * Pull and tag.
 */
fun Registry.pull(
    repository: String,
    tag: String,
    storage: Layout,
    platformResolver: ((Platform) -> Boolean)? = null,
) = repo(repository).pull(tag, storage, platformResolver)
