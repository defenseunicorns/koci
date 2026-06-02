/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable

/** Body of a `/v2/<repo>/tags/list` response. */
@Serializable internal data class TagsResponse(val name: String, val tags: List<String>)
