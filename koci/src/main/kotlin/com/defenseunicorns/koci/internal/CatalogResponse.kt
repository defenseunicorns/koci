/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable

/** Body of a `/v2/_catalog` response. */
@Serializable internal data class CatalogResponse(val repositories: List<String>)
