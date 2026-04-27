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
import kotlinx.serialization.json.Json

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
 * Permissive parser used only for failure-body decoding. Unknown enum values (e.g. error codes the
 * registry adds that aren't in our [ErrorCode] enum yet) fall back to [ErrorCode.UNKNOWN] via
 * [Json.coerceInputValues] + the default on [ActionableFailure.code]. Unknown JSON keys are
 * ignored so a registry adding new fields doesn't break log enrichment.
 */
private val FailureJson = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
}

private suspend fun HttpResponse.parseFailureResponse(): FailureResponse? {
  if (contentType() != ContentType.Application.Json) return null
  return try {
    FailureJson.decodeFromString<FailureResponse>(body<String>())
  } catch (_: NoTransformationFoundException) {
    null
  } catch (_: SerializationException) {
    null
  }
}
