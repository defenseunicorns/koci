/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import kotlinx.serialization.Serializable

/**
 * Response structure for repository tags list requests.
 *
 * Contains the repository name and its associated tags.
 *
 * @property name Repository name
 * @property tags List of tags associated with the repository, may be null if no tags exist
 */
@Serializable
class TagsResponse(val name: String, val tags: List<String>) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TagsResponse) return false
    if (name != other.name) return false
    return tags == other.tags
  }

  override fun hashCode(): Int {
    var result = name.hashCode()
    result = 31 * result + tags.hashCode()
    return result
  }

  override fun toString(): String = "TagsResponse(name='$name', tags=$tags)"
}
