/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/**
 * Single ingress for every HTTP request koci makes against an upstream registry.
 *
 * The wrapper turns engine-level exceptions (transport failure, timeout, engine closed, decode
 * threw) into `null`, then splits status-code branching once: 2xx goes to [onSuccess], everything
 * else is parsed into a [FailureResponse], logged, and handed to [onError] (which defaults to
 * producing `null`). Internal call sites that want to act on specific [ErrorCode]s override
 * [onError]; everyone else gets log-and-null for free.
 *
 * The single catch logs once with [operation] as the tag — that's the place #658 will plug into.
 * [CancellationException] is rethrown so coroutine cancellation propagates correctly.
 */
internal class HttpWrapper(private val client: HttpClient) {

  /**
   * Run [buildRequest], dispatch to [onSuccess] on a 2xx response or [onError] on anything else,
   * and return whatever the chosen lambda produced. Returns `null` if the engine throws or either
   * lambda throws.
   */
  @Suppress("detekt:UnusedParameter", "detekt:TooGenericExceptionCaught")
  suspend fun <T> call(
    operation: String,
    buildRequest: HttpRequestBuilder.() -> Unit,
    onError: suspend (FailureResponse) -> T? = { null },
    onSuccess: suspend (HttpResponse) -> T?,
  ): T? =
    try {
      val request = HttpRequestBuilder().apply(buildRequest)
      client.prepareRequest(request).execute { res ->
        when (res.status.isSuccess()) {
          true -> onSuccess(res)
          false -> {
            val failure = res.toFailureResponse()
            // TODO: #658 - log structured failure: operation, failure
            onError(failure)
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // TODO: #658 - log exception: operation
      null
    }
}

@Serializable private data class FailureEnvelope(val errors: List<ActionableFailure> = emptyList())

/**
 * Build a [FailureResponse] from a non-2xx [HttpResponse]: parse the OCI error envelope when the
 * body is JSON, otherwise create a single [ErrorCode.UNKNOWN] entry so callers always get a
 * non-empty [FailureResponse.errors].
 */
private suspend fun HttpResponse.toFailureResponse(): FailureResponse {
  val parsedErrors =
    when (contentType()) {
      ContentType.Application.Json ->
        try {
          body<FailureEnvelope>().errors
        } catch (_: Exception) {
          emptyList()
        }
      else -> emptyList()
    }
  val effective =
    parsedErrors.ifEmpty {
      listOf(ActionableFailure(code = ErrorCode.UNKNOWN, message = "HTTP ${status.value}"))
    }
  return FailureResponse(status = status, errors = effective)
}
