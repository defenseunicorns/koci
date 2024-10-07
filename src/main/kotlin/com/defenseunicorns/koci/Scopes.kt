/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.util.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Actions used in scopes.
// Reference: https://docs.docker.com/registry/spec/auth/scope/
// https://github.com/distribution/distribution/blob/v2.7.1/registry/handlers/app.go#L908

/** ACTION_PULL represents generic read access for resources of the repository type. */
const val ACTION_PULL = "pull"

/** ACTION_PUSH represents generic write access for resources of the repository type. */
const val ACTION_PUSH = "push"

/** ACTION_DELETE represents the delete permission for resources of the repository type. */
const val ACTION_DELETE = "delete"

/** SCOPE_REGISTRY_CATALOG is the scope for registry catalog access. */
const val SCOPE_REGISTRY_CATALOG = "registry:catalog:*"

fun scopeRepository(repo: String, vararg actions: String): String {
    val cleaned = cleanActions(actions.toList())

    return listOf("repository", repo, cleaned.joinToString(",")).joinToString(":")
}

fun cleanActions(scopes: List<String>): List<String> {
    val cleaned = scopes.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    if (cleaned.contains("*")) {
        return listOf("*")
    }
    return cleaned
}

// mirrored from CleanScopes
@Suppress(
    "detekt:LongMethod",
    "detekt:CyclomaticComplexMethod",
    "detekt:NestedBlockDepth",
    "detekt:ReturnCount",
    "detekt:LoopWithTooManyJumpStatements"
)
fun cleanScopes(scopes: List<String>): List<String> {
    // fast paths
    if (scopes.isEmpty()) return emptyList()
    if (scopes.size == 1) {
        val scope = scopes[0]
        val i = scope.lastIndexOf(":")
        if (i == -1) return listOf(scope)

        val actionList = cleanActions(scope.substring(i + 1).split(","))
        if (actionList.isEmpty()) return emptyList()

        val actions = actionList.joinToString(",")
        return listOf(scope.substring(0, i + 1) + actions)
    }

    // slow path
    val set = TreeSet<String>()
    val resourceTypes = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()

    for (scope in scopes) {
        val i = scope.indexOf(":")
        if (i == -1) {
            set.add(scope)
            continue
        }

        val resourceType = scope.substring(0, i)
        val rest = scope.substring(i + 1)
        val actionsDivider = rest.lastIndexOf(":")
        if (actionsDivider == -1) {
            set.add(scope)
            continue
        }
        val actions = rest.substring(actionsDivider + 1)
        if (actions.isEmpty()) continue
        val resourceName = rest.substring(0, actionsDivider)

        resourceTypes.getOrPut(resourceType) { mutableMapOf() }.getOrPut(resourceName) { mutableSetOf() }
            .addAll(actions.split(",").filter { it.isNotEmpty() })
    }

    for ((resourceType, namedActions) in resourceTypes) {
        for ((resourceName, actionSet) in namedActions) {
            if (actionSet.isEmpty()) continue

            val actions = if ("*" in actionSet) listOf("*") else actionSet.sorted()
            val scope = "$resourceType:$resourceName:${actions.joinToString(",")}"
            set.add(scope)
        }
    }

    return set.toList()
}

internal val scopesKey = AttributeKey<List<String>>("ociScopesKey")
internal val clientIDKey = AttributeKey<String>("ociClientIDKey")
private const val DEFAULT_CLIENT_ID = "koci"

internal fun Attributes.appendScopes(vararg scopes: String) {
    val current = getOrNull(scopesKey)
    if (current == null) {
        put(scopesKey, scopes.toList())
    } else {
        put(scopesKey, cleanScopes(current + scopes.toList()))
    }
}

/**
 * fetchDistributionToken fetches an access token as defined by the distribution
 *  specification.
 *  It fetches anonymous tokens if no credential is provided.
 *  References:
 *  - https://docs.docker.com/registry/spec/auth/jwt/
 *  - https://docs.docker.com/registry/spec/auth/token/
 */
@Suppress("detekt:SpreadOperator")
private suspend fun HttpClient.fetchDistributionToken(
    realm: String,
    service: String,
    scopes: List<String>,
    username: String,
    password: String,
): String {
    val res = get(realm) {
        if (username.isNotEmpty() || password.isNotEmpty()) {
            basicAuth(username, password)
        }
        url {
            if (service.isNotEmpty()) {
                parameters.append("service", service)
            }
            scopes.forEach { scope ->
                parameters.append("scope", scope)
            }
        }
        attributes.appendScopes(*scopes.toTypedArray())
    }

    if (res.status != HttpStatusCode.OK) {
        throw OCIException.UnexpectedStatus(HttpStatusCode.OK, res)
    }

    // As specified in https://docs.docker.com/registry/spec/auth/token/ section
    // "Token Response Fields", the token is either in `token` or
    // `access_token`. If both present, they are identical.
    @Serializable
    data class TokenResponse(
        val token: String,
        @SerialName("access_token") val accessToken: String? = null,
    )

    val json = Json {
        ignoreUnknownKeys = true
    }
    val tokenResponse: TokenResponse = json.decodeFromString(res.body())

    if (tokenResponse.accessToken != null) {
        return tokenResponse.accessToken
    }
    if (tokenResponse.token.isNotEmpty()) {
        return tokenResponse.token
    }
    throw OCIException.EmptyTokenReturned(res)
}

/**
 * Credential contains authentication credentials used to access remote registries.
 */
data class Credential(
    /**
     * Username is the name of the user for the remote registry.
     */
    val username: String,
    /**
     * Password is the secret associated with the username.
     */
    val password: String,
    /**
     * RefreshToken is a bearer token to be sent to the authorization service
     * for fetching access tokens.
     *
     * A refresh token is often referred as an identity token.
     *
     * [Reference](https://docs.docker.com/registry/spec/auth/oauth/)
     */
    val refreshToken: String,
    /**
     * AccessToken is a bearer token to be sent to the registry.
     *
     * An access token is often referred as a registry token.
     *
     * [Reference](https://docs.docker.com/registry/spec/auth/token/)
     */
    val accessToken: String,
) {
    /**
     * Returns `true` if all properties are empty.
     */
    fun isEmpty(): Boolean {
        return username.isEmpty() && password.isEmpty() && refreshToken.isEmpty() && accessToken.isEmpty()
    }

    /**
     * Returns `true` if any property is not empty.
     */
    fun isNotEmpty(): Boolean {
        return username.isNotEmpty() || password.isNotEmpty() || refreshToken.isNotEmpty() || accessToken.isNotEmpty()
    }
}

/**
 * fetchOAuth2Token fetches an OAuth2 access token.
 *
 * [Reference](https://docs.docker.com/registry/spec/auth/oauth/)
 */
@Suppress("detekt:ThrowsCount", "detekt:SpreadOperator")
private suspend fun HttpClient.fetchOAuth2Token(
    realm: String,
    service: String,
    scopes: List<String>,
    cred: Credential,
): String {
    val res = post(realm) {
        contentType(ContentType.Application.FormUrlEncoded)
        formData {
            // little redundant, but it makes linter / IDE happier this way
            require(
                cred.refreshToken.isNotEmpty() || (cred.username.isNotEmpty() && cred.password.isNotEmpty())
            ) { "missing username or password for bearer auth" }
            if (cred.refreshToken.isNotEmpty()) {
                append("grant_type", "refresh_token")
                append("refresh_token", cred.refreshToken)
            } else if (cred.username.isNotEmpty() && cred.password.isNotEmpty()) {
                append("grant_type", "password")
                append("username", cred.username)
                append("password", cred.password)
            }

            append("service", service)

            val clientID = attributes.getOrNull(clientIDKey)
            append("client_id", clientID ?: DEFAULT_CLIENT_ID)

            if (scopes.isNotEmpty()) {
                append("scope", scopes.joinToString(" "))
            }

            attributes.appendScopes(*scopes.toTypedArray())
        }
    }

    if (res.status != HttpStatusCode.OK) {
        throw OCIException.UnexpectedStatus(HttpStatusCode.OK, res)
    }

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
    )

    val json = Json {
        ignoreUnknownKeys = true
    }
    val tokenResponse: TokenResponse = json.decodeFromString(res.body())

    if (tokenResponse.accessToken.isNotEmpty()) {
        return tokenResponse.accessToken
    }

    throw OCIException.EmptyTokenReturned(res)
}

class OCIAuthPluginConfig {
    var cred: Credential = Credential("", "", "", "")

    // TODO: figure out what this is for and if we need it
    var forceAttemptOAuth2 = false
}

val OCIAuthPlugin = createClientPlugin("OCIAuthPlugin", ::OCIAuthPluginConfig) {
    val tokenCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    on(Send) { request ->
        val originalCall = proceed(request)
        originalCall.response.run { // this: HttpResponse
            if (status == HttpStatusCode.Unauthorized) {
                var scopes = emptyList<String>()
                var token: String? = null
                val registryKey = request.url.build().hostWithPort

                val authHeader =
                    parseAuthorizationHeader(headers[HttpHeaders.WWWAuthenticate]!!) as HttpAuthHeader.Parameterized
                val requestScopes = request.attributes.getOrNull(scopesKey)

                when (authHeader.authScheme) {
                    AuthScheme.Basic -> {
                        val cred = pluginConfig.cred
                        require(cred.isNotEmpty()) { "credential required for basic auth" }
                        require(cred.username.isNotEmpty() && cred.password.isNotEmpty()) {
                            "missing username or password for basic auth"
                        }
                        if (requestScopes != null) {
                            scopes = requestScopes
                        }
                        request.basicAuth(cred.username, cred.password)
                    }

                    AuthScheme.Bearer -> {
                        val realm = authHeader.parameters.first { it.name == "realm" }.value
                        val service = authHeader.parameters.firstOrNull { it.name == "service" }?.value ?: ""
                        val challengeScopes = authHeader.parameters.first { it.name == "scope" }.value.split(" ")

                        scopes = if (requestScopes != null) {
                            cleanScopes(challengeScopes + requestScopes)
                        } else {
                            cleanScopes(challengeScopes)
                        }

                        // attempt req w/ cached token based upon scopes
                        val cachedToken = tokenCache[registryKey]?.get(scopes.joinToString(" "))
                        if (cachedToken != null) {
                            request.bearerAuth(cachedToken)
                            val cacheAttempt = proceed(request)
                            if (cacheAttempt.response.status.isSuccess()) {
                                return@on cacheAttempt
                            }
                        }

                        val cred = pluginConfig.cred
                        token =
                            if (cred.isEmpty() || (cred.refreshToken.isEmpty() && !pluginConfig.forceAttemptOAuth2)) {
                                client.fetchDistributionToken(realm, service, scopes, cred.username, cred.password)
                            } else {
                                client.fetchOAuth2Token(realm, service, scopes, cred)
                            }

                        request.bearerAuth(token)
                    }
                }
                proceed(request).also {
                    if (it.response.status.isSuccess() && token != null) {
                        tokenCache[registryKey]?.set(scopes.joinToString(" "), token)
                    }
                }
            } else {
                originalCall
            }
        }
    }
}
