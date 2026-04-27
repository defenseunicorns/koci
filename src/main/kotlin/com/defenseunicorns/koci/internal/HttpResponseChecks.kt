/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * Single-line helper for any HTTP call that should branch on "did this succeed?".
 *
 * Returns `true` for 2xx responses, `false` otherwise. Once a logging framework is chosen, the
 * non-success path should log the operation, status, and parsed OCI failure body — see TODO below.
 *
 * ```
 * if (!response.succeeded("repository.tags")) return emptyList()
 * ```
 */
@Suppress("detekt:UnusedParameter")
internal fun HttpResponse.succeeded(operation: String): Boolean {
  if (status.isSuccess()) return true
  // TODO: MOBILE-198 - Log $operation failure (status + body) once a logger framework is wired up.
  return false
}
