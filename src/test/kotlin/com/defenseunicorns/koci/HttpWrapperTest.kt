/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.internal.HttpWrapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

internal class HttpWrapperTest {

  @Test
  fun `2xx response returns the decoded value`() = runTest {
    withWrapper({ respondOk("hello") }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          mapResponse = { res -> res.bodyAsText() },
        )
      assertEquals("hello", outcome)
    }
  }

  @Test
  fun `wrapper does not pre-check status — non-2xx still returns the decoded value`() = runTest {
    withWrapper({ respondError(HttpStatusCode.NotFound) }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          mapResponse = { res -> res.status.value },
        )
      assertEquals(404, outcome)
    }
  }

  @Test
  fun `mapResponse receives the live response`() = runTest {
    withWrapper({
      respond("body", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
    }) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          mapResponse = { res -> Pair(res.headers[HttpHeaders.ContentType], res.bodyAsText()) },
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
        mapResponse = { res -> res.status.value },
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
          mapResponse = { it.bodyAsText() },
        )
      assertEquals(null, outcome)
    }
  }

  @Test
  fun `mapResponse exception is caught and returns null`() = runTest {
    withWrapper({ respondOk() }) { w ->
      val outcome =
        w.call<Unit>(
          operation = "op",
          buildRequest = { url("https://test/") },
          mapResponse = { _ -> error("decode blew up") },
        )
      assertEquals(null, outcome)
    }
  }

  @Test
  fun `request timeout is caught and returns null`() = runTest {
    withWrapper(
      handler = {
        delay(500)
        respondOk()
      },
      timeoutMs = 50,
    ) { w ->
      val outcome =
        w.call(
          operation = "op",
          buildRequest = { url("https://test/") },
          mapResponse = { it.bodyAsText() },
        )
      assertEquals(null, outcome)
    }
  }

  private suspend fun <T> withWrapper(
    handler: MockRequestHandler,
    timeoutMs: Long? = null,
    block: suspend (HttpWrapper) -> T,
  ): T =
    HttpClient(MockEngine) {
        engine { addHandler(handler) }
        if (timeoutMs != null) {
          install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
        }
      }
      .use { block(HttpWrapper(it)) }
}
