/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException

/**
 * Single-line helper for any HTTP call that should branch on "did this succeed?".
 *
 * Returns `true` for 2xx responses, `false` otherwise. On non-success, parses the OCI spec
 * [FailureResponse] body when present so the log line carries the registry's [ErrorCode]s and
 * messages — far more useful than just the HTTP status. Once a logging framework is wired up
 * (MOBILE-198), the parsed result is what the log line should include.
 *
 * ```
 * if (!response.succeeded("repository.tags")) return emptyList()
 * ```
 */
@Suppress("detekt:UnusedParameter")
internal suspend fun HttpResponse.succeeded(operation: String): Boolean {
  if (status.isSuccess()) return true
  val parsed = parseFailureResponse()
  // TODO: MOBILE-198 - Log $operation failure once a logger framework is wired up.
  //   Should include: status, parsed?.errors (when non-null) or "no OCI body".
  @Suppress("UNUSED_EXPRESSION") parsed
  return false
}

/**
 * Decodes the response body as an OCI spec [FailureResponse] using ktor's `ContentNegotiation`
 * (configured in [com.defenseunicorns.koci.api.Koci] with `ignoreUnknownKeys` / `coerceInputValues`
 * so unknown enum codes fall back to [ErrorCode.UNKNOWN]). Returns null when the response is not
 * JSON or when the body is unparseable.
 */
private suspend fun HttpResponse.parseFailureResponse(): FailureResponse? {
  if (contentType() != ContentType.Application.Json) return null
  return try {
    body<FailureResponse>()
  } catch (_: NoTransformationFoundException) {
    null
  } catch (_: SerializationException) {
    null
  }
}
