/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.TestFixtures.fakeRegistry
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class RegistryTest {

  @Test
  fun `repo returns repository with the given name`() {
    val registry = fakeRegistry(handler = { respondOk() })
    assertEquals("library/ubuntu", registry.repo("library/ubuntu").name)
  }

  @Test
  fun `ping returns true when registry responds 200`() = runTest {
    val registry = fakeRegistry(handler = { respondOk() })
    assertTrue(registry.ping())
  }

  @Test
  fun `ping returns false when registry responds 4xx`() = runTest {
    val registry = fakeRegistry(handler = { respondError(HttpStatusCode.Unauthorized) })
    assertFalse(registry.ping())
  }

  @Test
  fun `ping returns false on transport failure`() = runTest {
    val registry = fakeRegistry(handler = { throw IOException("connection refused") })
    assertFalse(registry.ping())
  }

  @Test
  fun `catalog emits repositories from a single page response`() = runTest {
    val registry =
      fakeRegistry(
        handler = {
          respond(
            content = """{"repositories":["myorg/app","myorg/lib"]}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
          )
        }
      )
    val pages = registry.catalog().toList()
    assertEquals(1, pages.size)
    assertEquals(listOf("myorg/app", "myorg/lib"), pages.first().map { it.name })
  }

  @Test
  fun `catalog follows Link header to collect all pages`() = runTest {
    var requestCount = 0
    val registry =
      fakeRegistry(
        handler = {
          requestCount++
          when (requestCount) {
            1 ->
              respond(
                content = """{"repositories":["repo-a"]}""",
                status = HttpStatusCode.OK,
                headers =
                  headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.Link to listOf("""</v2/_catalog?n=100&last=repo-a>; rel="next""""),
                  ),
              )
            else ->
              respond(
                content = """{"repositories":["repo-b"]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
              )
          }
        }
      )
    val pages = registry.catalog(n = 100).toList()
    assertEquals(2, pages.size)
    assertEquals(listOf("repo-a"), pages[0].map { it.name })
    assertEquals(listOf("repo-b"), pages[1].map { it.name })
  }

  @Test
  fun `catalog emits nothing on transport failure`() = runTest {
    val registry = fakeRegistry(handler = { throw IOException("network down") })
    val pages = registry.catalog().toList()
    assertTrue(pages.isEmpty())
  }
}
