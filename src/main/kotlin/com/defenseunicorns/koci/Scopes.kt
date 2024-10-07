/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.util.*
import java.util.*

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

fun cleanActions(actions: List<String>): List<String> {
    val cleaned = actions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

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
val clientIDKey = AttributeKey<String>("ociClientIDKey")
internal const val DEFAULT_CLIENT_ID = "koci"

internal fun Attributes.appendScopes(vararg scopes: String) {
    val current = getOrNull(scopesKey)
    if (current == null) {
        put(scopesKey, cleanScopes(scopes.toList()))
    } else {
        put(scopesKey, cleanScopes(current + scopes.toList()))
    }
}
