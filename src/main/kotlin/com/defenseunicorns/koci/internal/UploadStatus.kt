/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/** UploadStatus tracks the server-side state of an upload. */
internal data class UploadStatus(val location: String, var offset: Long, var minChunkSize: Long)
