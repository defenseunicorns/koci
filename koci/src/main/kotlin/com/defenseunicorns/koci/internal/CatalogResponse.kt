/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable

/**
 * Response structure for repository catalog requests.
 *
 * Contains a list of repository names available in the registry.
 *
 * @property repositories List of repository names in the registry
 */
@Serializable internal data class CatalogResponse(val repositories: List<String>)
