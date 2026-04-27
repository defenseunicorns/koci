/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.ActionableFailure
import com.defenseunicorns.koci.api.ErrorCode
import com.defenseunicorns.koci.api.FailureResponse
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * Extracts an OCI spec [FailureResponse] from an HTTP response when the status indicates failure.
 *
 * Returns null on a successful response. For non-success responses:
 * - If the body is `application/json` and parses as [FailureResponse], returns it with `status`
 *   set.
 * - Otherwise, returns a synthetic [FailureResponse] carrying the status and a single
 *   [ActionableFailure] with [ErrorCode.UNKNOWN] so callers always get structured info.
 *
 * Replaces the v1 `attemptThrow4XX` helper — instead of installing a `HttpResponseValidator` that
 * throws on 4xx, callers `expectSuccess = false` (default), receive the response, and call this to
 * extract the structured failure.
 */
internal suspend fun HttpResponse.failureResponseOrNull(): FailureResponse? {
  if (status.isSuccess()) return null

  if (contentType() == ContentType.Application.Json) {
    val parsed =
      try {
        body<FailureResponse>()
      } catch (_: NoTransformationFoundException) {
        null
      } catch (_: kotlinx.serialization.SerializationException) {
        null
      }
    if (parsed != null) {
      parsed.status = status
      return parsed
    }
  }

  // Non-JSON or unparseable failure body — synthesize a minimal FailureResponse so callers see the
  // status code and have a structured value to branch on.
  return FailureResponse(
      listOf(ActionableFailure(code = ErrorCode.UNKNOWN, message = "HTTP ${status.value}"))
    )
    .also { it.status = status }
}
