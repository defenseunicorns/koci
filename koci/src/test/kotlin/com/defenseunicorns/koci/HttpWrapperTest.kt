/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.internal.ErrorCode
import com.defenseunicorns.koci.internal.FailureResponse
import com.defenseunicorns.koci.internal.HttpWrapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class HttpWrapperTest {

  @Test
  fun `2xx response routes to onSuccess`() = runTest {
    withWrapper({ respondOk("hello") }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onSuccess = { res -> res.bodyAsText() },
        )
      assertEquals("hello", outcome)
    }
  }

  @Test
  fun `non-2xx response routes to onError and skips onSuccess`() = runTest {
    var successFired = false
    withWrapper({ respondError(HttpStatusCode.NotFound) }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onError = { failure -> failure.status.value },
          onSuccess = {
            successFired = true
            -1
          },
        )
      assertEquals(404, outcome)
      assertEquals(false, successFired)
    }
  }

  @Test
  fun `onError receives UNKNOWN failure when body is not JSON`() = runTest {
    withWrapper({ respondError(HttpStatusCode.InternalServerError) }) { w ->
      val outcome =
        w.call<FailureResponse>(
          operation = "op",
          buildRequest = { url("https://test/") },
          onError = { failure -> failure },
          onSuccess = { null },
        )
      assertEquals(HttpStatusCode.InternalServerError, outcome?.status)
      assertEquals(1, outcome?.errors?.size)
      assertEquals(ErrorCode.UNKNOWN, outcome?.errors?.first()?.code)
    }
  }

  @Test
  fun `onError receives parsed FailureResponse when body is OCI JSON`() = runTest {
    val body =
      """{"errors":[{"code":"DENIED","message":"requested access to the resource is denied"}]}"""
    withWrapper(
      { _ ->
        respond(
          body,
          HttpStatusCode.Forbidden,
          headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
      },
      withJson = true,
    ) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onError = { failure -> failure },
          onSuccess = { null },
        )
      assertEquals(HttpStatusCode.Forbidden, outcome?.status)
      assertEquals(1, outcome?.errors?.size)
      assertEquals(ErrorCode.DENIED, outcome?.errors?.first()?.code)
      assertEquals("requested access to the resource is denied", outcome?.errors?.first()?.message)
    }
  }

  @Test
  fun `default onError returns null for non-2xx responses`() = runTest {
    withWrapper({ respondError(HttpStatusCode.InternalServerError) }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onSuccess = { res -> res.bodyAsText() },
        )
      assertEquals(null, outcome)
    }
  }

  @Test
  fun `onSuccess receives the real response`() = runTest {
    withWrapper({
      respond("body", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
    }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onSuccess = { res -> Pair(res.headers[HttpHeaders.ContentType], res.bodyAsText()) },
        )
      assertIs<Pair<String?, String>>(outcome)
      assertEquals("text/plain", outcome.first)
      assertEquals("body", outcome.second)
    }
  }

  @Test
  fun `buildRequest configures the outgoing request`() = runTest {
    var capturedMethod: HttpMethod? = null
    var capturedUrl: String? = null
    var capturedHeader: String? = null
    withWrapper({ req ->
      capturedMethod = req.method
      capturedUrl = req.url.toString()
      capturedHeader = req.headers["X-Test"]
      respondOk()
    }) { w ->
      w.call(
        operation = "op",
        buildRequest = {
          method = HttpMethod.Post
          url("https://example.com/path")
          headers.append("X-Test", "value")
        },
        onSuccess = { res -> res.status.value },
      )
    }

    assertEquals(HttpMethod.Post, capturedMethod)
    assertEquals("https://example.com/path", capturedUrl)
    assertEquals("value", capturedHeader)
  }

  @Test
  fun `engine exception is caught and returns null`() = runTest {
    withWrapper({ throw IOException("connect refused") }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onSuccess = { it.bodyAsText() },
        )
      assertEquals(null, outcome)
    }
  }

  @Test
  fun `onSuccess exception is caught and returns null`() = runTest {
    withWrapper({ respondOk() }) { w ->
      val outcome =
        w.call<Unit>(
          operation = "op",
          buildRequest = { url("https://test/") },
          onSuccess = { _ -> error("decode blew up") },
        )
      assertEquals(null, outcome)
    }
  }

  @Test
  fun `onError exception is caught and returns null`() = runTest {
    withWrapper({ respondError(HttpStatusCode.BadRequest) }) { w ->
      val outcome =
        w.call<Unit>(
          operation = "op",
          buildRequest = { url("https://test/") },
          onError = { _ -> error("error handler blew up") },
          onSuccess = { _ -> error("should not run") },
        )
      assertEquals(null, outcome)
    }
  }

  @Test
  fun `request timeout is caught and returns null`() = runTest {
    withWrapper(
      handler = {
        delay(500.milliseconds)
        respondOk()
      },
      timeoutMs = 50,
    ) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          onSuccess = { it.bodyAsText() },
        )
      assertEquals(null, outcome)
    }
  }

  private suspend fun <T> withWrapper(
    handler: MockRequestHandler,
    timeoutMs: Long? = null,
    withJson: Boolean = false,
    block: suspend (HttpWrapper) -> T,
  ): T =
    HttpClient(MockEngine) {
        engine { addHandler(handler) }
        if (timeoutMs != null) {
          install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
        }
        if (withJson) {
          install(ContentNegotiation) {
            json(
              Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
              }
            )
          }
        }
      }
      .use { block(HttpWrapper(it, TestFixtures.NoOpLogger)) }
}
