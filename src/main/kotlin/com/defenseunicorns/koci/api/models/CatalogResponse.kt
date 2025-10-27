/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import kotlinx.serialization.Serializable

/**
 * Response structure for repository catalog requests.
 *
 * Contains a list of repository names available in the registry.
 *
 * @property repositories List of repository names in the registry
 */
@Serializable
class CatalogResponse(val repositories: List<String>) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CatalogResponse) return false
    return repositories == other.repositories
  }

  override fun hashCode(): Int {
    return repositories.hashCode()
  }

  override fun toString(): String = "CatalogResponse(repositories=$repositories)"
}
