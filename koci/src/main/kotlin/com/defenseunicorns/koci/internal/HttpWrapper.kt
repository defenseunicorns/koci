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
 * Single entry point for every HTTP request koci makes against an upstream registry. Engine
 * exceptions (transport failure, timeout, engine closed, deserialization throws) become `null`.
 * Status branching happens once: 2xx goes to [onSuccess], everything else is parsed into a
 * [FailureResponse] and handed to [onError], which defaults to logging and returning `null`.
 * [CancellationException] is rethrown so coroutine cancellation propagates.
 */
internal class HttpWrapper(private val client: HttpClient, private val logger: KociLogger) {

  /**
   * Builds a request via [buildRequest], dispatches to [onSuccess] on a 2xx response or [onError]
   * otherwise, and returns whatever the chosen lambda produced. Returns `null` if the engine throws
   * or either lambda throws.
   */
  @Suppress("detekt:TooGenericExceptionCaught")
  suspend fun <T> call(
    operation: String,
    buildRequest: HttpRequestBuilder.() -> Unit,
    onError: suspend (FailureResponse) -> T? = {
      logger.warn { "[$operation] call failed: $it" }
      null
    },
    onSuccess: suspend (HttpResponse) -> T?,
  ): T? =
    try {
      val request = HttpRequestBuilder().apply(buildRequest)
      client.prepareRequest(request).execute { res ->
        try {
          when (res.status.isSuccess()) {
            true -> onSuccess(res)
            false -> onError(res.toFailureResponse())
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.error(e) { "[$operation] handler exception: ${e::class.simpleName}" }
          null
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.error(e) { "[$operation] unexpected exception: ${e::class.simpleName}" }
      null
    }
}

@Serializable private data class FailureEnvelope(val errors: List<ActionableFailure> = emptyList())

/**
 * Builds a [FailureResponse] from a non-2xx [HttpResponse]. Parses the OCI error envelope when the
 * body is JSON; otherwise returns a single [ErrorCode.UNKNOWN] entry so [FailureResponse.errors] is
 * never empty.
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
