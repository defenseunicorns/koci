/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Single-line helper for any HTTP call that should branch on "did this succeed?".
 *
 * Returns `true` for 2xx responses, `false` otherwise. On non-success, parses the OCI spec
 * [FailureResponse] body when present so the log line carries the registry's [ErrorCode]s and
 * messages.
 *
 * ```
 * if (!response.succeeded("repository.tags")) return emptyList()
 * ```
 */
@Suppress("detekt:UnusedParameter")
internal suspend fun HttpResponse.succeeded(operation: String): Boolean {
  if (status.isSuccess()) {
    return true
  }

  // TODO: MOBILE-198 - Log

  if (contentType() != ContentType.Application.Json) {
    return false
  }

  val failure =
    try {
      body<FailureResponse>()
    } catch (_: Exception) {
      null
    }

  return false
}
