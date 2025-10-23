/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.auth

import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import java.util.TreeSet

/**
 * This file contains scope-related functionality for OCI registry authentication.
 *
 * Scopes define the access permissions required for different registry operations. They follow the
 * format: `resourceType:resourceName:actions`
 *
 * Reference: https://docs.docker.com/registry/spec/auth/scope/
 */

/** ACTION_PULL represents generic read access for resources of the repository type. */
const val ACTION_PULL = "pull"

/** ACTION_PUSH represents generic write access for resources of the repository type. */
const val ACTION_PUSH = "push"

/** ACTION_DELETE represents the delete permission for resources of the repository type. */
const val ACTION_DELETE = "delete"

/** SCOPE_REGISTRY_CATALOG is the scope for registry catalog access. */
const val SCOPE_REGISTRY_CATALOG = "registry:catalog:*"

/**
 * Creates a repository scope string for authentication.
 *
 * Formats a scope string in the form `repository:name:actions` where actions are comma-separated.
 * Actions are cleaned and deduplicated.
 *
 * @param repo Repository name
 * @param actions One or more actions (pull, push, delete, etc.)
 * @return Formatted scope string
 */
fun scopeRepository(repo: String, vararg actions: String): String {
  val cleaned = cleanActions(actions.toList())

  return listOf("repository", repo, cleaned.joinToString(",")).joinToString(":")
}

/**
 * Cleans and normalizes a list of actions.
 *
 * Removes duplicates, trims whitespace, sorts alphabetically, and handles wildcard actions. If "*"
 * is present, it returns only "*".
 *
 * @param actions List of action strings to clean
 * @return Cleaned list of actions
 */
fun cleanActions(actions: List<String>): List<String> {
  val cleaned = actions.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

  if (cleaned.contains("*")) {
    return listOf("*")
  }
  return cleaned
}

/**
 * Cleans and normalizes a list of scope strings.
 *
 * Processes multiple scope strings, combining and deduplicating actions for the same resource type
 * and name. Handles special cases and malformed scopes.
 *
 * @param scopes List of scope strings to clean
 * @return Normalized list of scope strings
 */
@Suppress(
  "detekt:LongMethod",
  "detekt:CyclomaticComplexMethod",
  "detekt:NestedBlockDepth",
  "detekt:ReturnCount",
  "detekt:LoopWithTooManyJumpStatements",
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

/** Attribute key for storing authentication scopes in Ktor request attributes. */
internal val scopesKey = AttributeKey<List<String>>("ociScopesKey")

/** Attribute key for storing client ID in Ktor request attributes. */
val clientIDKey = AttributeKey<String>("ociClientIDKey")

/** Default client ID used for authentication if none is provided. */
internal const val DEFAULT_CLIENT_ID = "koci"

/**
 * Extension function to append scopes to Ktor request attributes.
 *
 * Adds the provided scopes to any existing scopes in the attributes, cleaning and normalizing the
 * combined list.
 *
 * @param scopes One or more scope strings to append
 */
internal fun Attributes.appendScopes(vararg scopes: String) {
  val current = getOrNull(scopesKey)
  if (current == null) {
    put(scopesKey, cleanScopes(scopes.toList()))
  } else {
    put(scopesKey, cleanScopes(current + scopes.toList()))
  }
}
