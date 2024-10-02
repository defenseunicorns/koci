/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Actions used in scopes.
// Reference: https://docs.docker.com/registry/spec/auth/scope/

// ACTION_PULL represents generic read access for resources of the repository
// type.
const val ACTION_PULL = "pull"

// ACTION_PUSH represents generic write access for resources of the
// repository type.
const val ACTION_PUSH = "push"

// ACTION_DELETE represents the delete permission for resources of the
// repository type.
const val ACTION_DELETE = "delete"

// ACTION_REGISTRY_CATALOG is the scope for registry catalog access.
const val ACTION_REGISTRY_CATALOG = "registry:catalog:*"

fun scopeRepository(repo: String, scopes: List<String>): String {
    val actions = cleanActions(scopes)

    return listOf("repository", repo, actions.joinToString(",")).joinToString(":")
}

private fun cleanActions(actions: List<String>): List<String> {
    return actions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

private val scopesKey = AttributeKey<String>("scopesKey")

val ScopesPlugin = createClientPlugin("ScopesPlugin") {
    on(Send) { request ->
        val originalCall = proceed(request)
        originalCall.response.run { // this: HttpResponse
            if (status == HttpStatusCode.Unauthorized && headers["WWW-Authenticate"]!!.contains("Bearer")) {
                val authHeader = parseAuthorizationHeader(headers["WWW-Authenticate"]!!) as HttpAuthHeader.Parameterized

                val realm = authHeader.parameters.first { it.name == "realm" }.value
                val service = authHeader.parameters.first { it.name == "service" }.value
                val scope = authHeader.parameters.first { it.name == "scope" }.value

                // do auth flow
                val authURL = URLBuilder().takeFrom(realm).apply {
                    parameters.append("scope", scope)
                    parameters.append("service", service)
                }.build()

                @Serializable
                data class TokenResponse(
                    val token: String,
                )

                val res = client.get(authURL) {
                    accept(ContentType.Application.Json)
                }

                val token = Json.decodeFromString<TokenResponse>(res.body())

                request.bearerAuth(token.token)

                proceed(request)
            } else {
                originalCall
            }
        }
    }
}
