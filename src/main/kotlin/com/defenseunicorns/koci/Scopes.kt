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
import java.util.*

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
    when (scopes.size) {
        0 -> return emptyList()
        1 -> {
            val scope = scopes[0]
            val i = scope.lastIndexOf(":")
            if (i == -1) {
                return listOf(scope)
            }
            var actionList = scope.substring(i + 1).split(",")
            actionList = cleanActions(actionList)
            if (actionList.isEmpty()) {
                return emptyList()
            }

            val actions = actionList.joinToString(",")
            return listOf(
                scope.substring(0, i + 1) + actions
            )
        }
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

        // extract resource name and actions
        val rest = scope.substring(i + 1)
        val actionsDivider = rest.lastIndexOf(":")
        if (actionsDivider == -1) {
            set.add(scope)
            continue
        }
        val actions = rest.substring(actionsDivider + 1)
        if (actions.isEmpty()) {
            // drop since no actions found
            continue
        }
        val resourceName = rest.substring(0, actionsDivider)

        val namedActions = resourceTypes.getOrPut(resourceType) { mutableMapOf() }

        for (action in actions.split(",")) {
            if (action.isNotEmpty()) {
                val actionSet = namedActions.getOrPut(resourceName) { mutableSetOf() }
                actionSet.add(action)
            }
        }
    }

    for ((resourceType, namedActions) in resourceTypes) {
        for ((resourceName, actionSet) in namedActions) {
            if (actionSet.isEmpty()) {
                continue
            }

            val actions = TreeSet<String>()
            for (action in actionSet) {
                if (action == "*") {
                    actions.clear()
                    actions.add("*")
                    break
                }
                actions.add(action)
            }

            val scope = "$resourceType:$resourceName:${actions.joinToString(",")}"
            set.add(scope)
        }
    }

    return set.toList()
}

internal val scopesKey = AttributeKey<List<String>>("scopesKey")

val ScopesPlugin = createClientPlugin("ScopesPlugin") {
    on(Send) { request ->
        val originalCall = proceed(request)
        originalCall.response.run { // this: HttpResponse
            if (status == HttpStatusCode.Unauthorized && headers["WWW-Authenticate"]!!.contains("Bearer")) {
                val authHeader = parseAuthorizationHeader(headers["WWW-Authenticate"]!!) as HttpAuthHeader.Parameterized

                val realm = authHeader.parameters.first { it.name == "realm" }.value
                val service = authHeader.parameters.first { it.name == "service" }.value
                val challengeScopes = authHeader.parameters.first { it.name == "scope" }.value.split(" ")

                val requestScopes = request.attributes.getOrNull(scopesKey)

                val scopes = if (requestScopes != null) {
                    cleanScopes(challengeScopes + requestScopes)
                } else {
                    cleanScopes(challengeScopes)
                }

                // do auth flow
                val authURL = URLBuilder().takeFrom(realm).apply {
                    parameters.append("scope", scopes.joinToString(" "))
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
