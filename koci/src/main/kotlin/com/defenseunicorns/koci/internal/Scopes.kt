/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import java.util.TreeSet

/**
 * OCI registry scopes used by the auth plugin. Scopes follow the
 * `resourceType:resourceName:actions` form documented at
 * <https://docs.docker.com/registry/spec/auth/scope/>.
 */

/** Read access on a repository. */
internal const val ACTION_PULL: String = "pull"

/** Write access on a repository. */
internal const val ACTION_PUSH: String = "push"

/** Delete access on a repository. */
internal const val ACTION_DELETE: String = "delete"

/** Catalog-wide read scope. */
internal const val SCOPE_REGISTRY_CATALOG: String = "registry:catalog:*"

/**
 * Builds a repository scope string `repository:<repo>:<actions>` from the given actions. Actions
 * are cleaned, deduplicated, and sorted.
 */
internal fun scopeRepository(repo: String, vararg actions: String): String {
  val cleaned = cleanActions(actions.toList())

  return listOf("repository", repo, cleaned.joinToString(",")).joinToString(":")
}

/** Trims, deduplicates, and sorts [actions]. Returns `["*"]` whenever a wildcard is present. */
internal fun cleanActions(actions: List<String>): List<String> {
  val cleaned = actions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

  if (cleaned.contains("*")) {
    return listOf("*")
  }
  return cleaned
}

/**
 * Canonicalizes [scopes] by combining actions for the same `resourceType:resourceName` pair,
 * deduplicating, and sorting. Malformed entries are preserved verbatim so they surface to the
 * server rather than being silently dropped.
 */
@Suppress(
  "detekt:LongMethod",
  "detekt:CyclomaticComplexMethod",
  "detekt:NestedBlockDepth",
  "detekt:ReturnCount",
  "detekt:LoopWithTooManyJumpStatements",
)
internal fun cleanScopes(scopes: List<String>): List<String> {
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

    resourceTypes
      .getOrPut(resourceType) { mutableMapOf() }
      .getOrPut(resourceName) { mutableSetOf() }
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

/** Attribute key holding the canonical scopes for a request. */
internal val scopesKey: AttributeKey<List<String>> = AttributeKey<List<String>>("ociScopesKey")

/** Merges [scopes] into the request's [scopesKey] attribute, cleaning the combined list. */
internal fun Attributes.appendScopes(vararg scopes: String) {
  val current = getOrNull(scopesKey)
  if (current == null) {
    put(scopesKey, cleanScopes(scopes.toList()))
  } else {
    put(scopesKey, cleanScopes(current + scopes.toList()))
  }
}
