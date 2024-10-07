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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class CatalogResponse(val repositories: List<String>)

@Serializable
data class TagsResponse(val name: String, val tags: List<String>?)

const val MANIFEST_MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json"
const val MANIFEST_CONFIG_MEDIA_TYPE = "application/vnd.oci.image.config.v1+json"
const val INDEX_MEDIA_TYPE = "application/vnd.oci.image.index.v1+json"

/**
 * Zarf-specific "multi" OS
 */
const val MULTI_OS = "multi"

private fun URLBuilder.paginate(n: Int, last: String? = null): URLBuilder = apply {
    parameters.append("n", n.toString())
    last?.let { parameters.append("last", it) }
}

class Router(registryURL: Url) {
    private val v2Prefix = "v2/"

    private val base: URLBuilder = URLBuilder().takeFrom(registryURL).appendPathSegments(v2Prefix)

    fun base(): Url {
        return base.build()
    }

    fun catalog(): Url {
        return base.clone().appendPathSegments("_catalog").build()
    }

    fun catalog(n: Int, lastRepo: String? = null): Url {
        val catalogBase = URLBuilder().takeFrom(catalog())
        return catalogBase.paginate(n, lastRepo).build()
    }

    fun tags(repository: String): Url {
        return base.clone().appendPathSegments(repository, "tags", "list").build()
    }

    fun manifest(repository: String, ref: String): Url {
        return base.clone().appendPathSegments(repository, "manifests", ref).build()
    }

    fun manifest(repository: String, descriptor: Descriptor): Url {
        return manifest(repository, descriptor.digest.toString())
    }

    fun blob(repository: String, descriptor: Descriptor): Url {
        return base.clone().appendPathSegments(repository, "blobs", descriptor.digest.toString())
            .build()
    }

    fun uploads(repository: String): Url {
        // the final "" allows for a trailing /
        return base.clone().appendPathSegments(repository, "blobs", "uploads", "").build()
    }

    /**
     * [RFC 7231](https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.2)
     */
    fun parseUploadLocation(locationHeader: String): Url {
        val uri = URI(locationHeader)
        if (uri.isAbsolute) {
            return URLBuilder().takeFrom(locationHeader).build()
        }

        return base.clone().appendPathSegments(locationHeader).build()
    }
}

class Registry private constructor(
    registryURL: String,
    val storage: Layout,
    var client: HttpClient,
) {
    val router = Router(URLBuilder().takeFrom(registryURL).build())
    val extensions = Extensions()

    init {
        client = client.config {
            headers {
                // https://distribution.github.io/distribution/spec/api/#api-version-check
                append("Docker-Distribution-API-Version", "registry/2.0")
            }

            HttpResponseValidator {
                handleResponseExceptionWithRequest { exception, _ ->
                    val clientException = exception as? ClientRequestException
                        ?: return@handleResponseExceptionWithRequest
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

    class Builder {
        private lateinit var registryURL: String
        private lateinit var storage: Layout
        private var client: HttpClient = HttpClient(CIO)

        fun registryURL(registryURL: String) = apply { this.registryURL = registryURL }
        fun storage(storage: Layout) = apply { this.storage = storage }
        fun client(client: HttpClient) = apply { this.client = client }

        fun build() = Registry(registryURL, storage, client)
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
        fun catalog(n: Int, lastRepo: String? = null): Flow<Result<CatalogResponse>> =
            flow {
                var endpoint: Url? = router.catalog(n, lastRepo)

                while (endpoint != null) {
                    val result: Result<CatalogResponse> = runCatching {
                        val response = client.get(endpoint!!) {
                            attributes.appendScopes(SCOPE_REGISTRY_CATALOG)
                        }

                        // If the header is not present, the client can assume that all results have been received.
                        val linkHeader = response.headers[HttpHeaders.Link]

                        endpoint = linkHeader?.let {
                            // TODO: change from regex to a full spec-compliant parser https://github.com/defenseunicorns-futures/project-fox/issues/129
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

                        Json.decodeFromString(response.body())
                    }

                    emit(result)

                    if (result.isFailure) {
                        break
                    }
                }
            }

        /**
         * _Not in the spec_, this combines the [catalog] and the [tags] endpoints to return a flattened
         * list of all repos in the registry alongside their tags
         */
        fun list(): Flow<Result<TagsResponse>> = channelFlow {
            // TODO: use catalog flow
            catalog().fold(
                onFailure = {
                    send(Result.failure(it))
                },
                onSuccess = { (repositories) ->
                    repositories.map { r ->
                        send(tags(r))
                    }
                }
            )
        }
    }
}

fun Registry.repo(name: String) = Repository(client, router, name)
suspend fun Registry.tags(repository: String) = repo(repository).tags()
suspend fun Registry.resolve(repository: String, tag: String, resolver: (Platform) -> Boolean = ::defaultResolver) =
    repo(repository).resolve(tag, resolver)

fun Registry.pull(repository: String, tag: String, resolver: (Platform) -> Boolean = ::defaultResolver) =
    repo(repository).pull(tag, storage, resolver)
