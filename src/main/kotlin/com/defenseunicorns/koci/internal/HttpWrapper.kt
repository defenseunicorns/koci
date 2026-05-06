/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single ingress for every HTTP request koci makes against an upstream registry.
 *
 * The wrapper has exactly one job: run the request and turn engine-level exceptions (transport
 * failure, timeout, engine closed, decode threw) into `null`. Status-code branching is the call
 * site's job — a non-2xx response is still a response, and [mapResponse] sees it.
 *
 * The single catch logs once with [operation] as the tag — that's the place MOBILE-198 will plug
 * into. [CancellationException] is rethrown so coroutine cancellation propagates correctly.
 */
internal class HttpWrapper(private val client: HttpClient) {

  /**
   * Run [buildRequest] and hand the live response to [mapResponse]. Returns whatever [mapResponse]
   * produced. Returns `null` only when an exception is thrown — by the engine or by [mapResponse].
   */
  @Suppress("detekt:UnusedParameter", "detekt:TooGenericExceptionCaught")
  suspend fun <T> call(
    operation: String,
    buildRequest: HttpRequestBuilder.() -> Unit,
    mapResponse: suspend (HttpResponse) -> T?,
  ): T? =
    try {
      val request = HttpRequestBuilder().apply(buildRequest)
      client.prepareRequest(request).execute { res -> mapResponse(res) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // TODO: MOBILE-198 - log exception
      null
    }
}
