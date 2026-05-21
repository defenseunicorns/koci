/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable

/** Contents of the `oci-layout` marker file at the root of an OCI Image Layout directory. */
@Serializable internal data class LayoutMarker(val imageLayoutVersion: String)
