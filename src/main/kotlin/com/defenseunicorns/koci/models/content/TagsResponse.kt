/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.content

import kotlinx.serialization.Serializable

/**
 * Response structure for repository tags list requests.
 *
 * Contains the repository name and its associated tags.
 *
 * @property name Repository name
 * @property tags List of tags associated with the repository, may be null if no tags exist
 */
@Serializable data class TagsResponse(val name: String, val tags: List<String>)
