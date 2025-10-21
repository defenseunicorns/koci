/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models

import kotlinx.serialization.Serializable

/**
 * Response structure for repository catalog requests.
 *
 * Contains a list of repository names available in the registry.
 *
 * @property repositories List of repository names in the registry
 */
@Serializable data class CatalogResponse(val repositories: List<String>)

/**
 * Response structure for repository tags list requests.
 *
 * Contains the repository name and its associated tags.
 *
 * @property name Repository name
 * @property tags List of tags associated with the repository, may be null if no tags exist
 */
@Serializable data class TagsResponse(val name: String, val tags: List<String>?)
