/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import kotlin.test.*
import org.junit.jupiter.api.assertDoesNotThrow

class ReferenceTest {
  @Test
  @Suppress("detekt:MaxLineLength", "detekt:LongMethod")
  fun good() {
    val testCases =
      mapOf(
        // valid form A
        "localhost:5000/library/registry@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20" to
          (Reference(
            "localhost:5000",
            "library/registry",
            "sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20",
          ) to
            "localhost:5000/library/registry@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20"),

        // valid form B
        "localhost:5000/library/registry:2.8.3@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20" to
          (Reference(
            "localhost:5000",
            "library/registry",
            "sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20",
            // note that the tag is lost upon parsing as a digest is a more specific reference
          ) to
            "localhost:5000/library/registry@sha256:1b640322f9a983281970daaeba1a6d303f399d67890644389ff419d951963e20"),

        // valid form C
        "localhost:5000/library/registry:2.8.3" to
          (Reference("localhost:5000", "library/registry", "2.8.3") to
            "localhost:5000/library/registry:2.8.3"),

        // valid form D
        "localhost:5000/huh" to (Reference("localhost:5000", "huh", "") to "localhost:5000/huh"),
        "test:5000/repo:tag" to (Reference("test:5000", "repo", "tag") to "test:5000/repo:tag"),
        "docker.io/lower:Upper" to
          (Reference("docker.io", "lower", "Upper") to "docker.io/lower:Upper"),
      )

    for ((tc, want) in testCases) {
      val got = Reference.parse(tc).getOrThrow()
      assertEquals(want.first, got)
      assertTrue(got.isNotEmpty())
      assertFalse(got.isEmpty())

      assertEquals(want.second, got.toString())

      if (got.reference.startsWith("sha256")) {
        assertDoesNotThrow { got.digest() }
      } else {
        assertFailsWith<IllegalArgumentException> { got.digest() }
      }
    }

    val urlTestCase = Reference(Url("https://localhost:5005"), "repo", "tag")

    assertEquals("localhost:5005/repo:tag", urlTestCase.toString())

    assertTrue(Reference("", "", "").isEmpty())
    assertFalse(Reference("", "", "").isNotEmpty())
    val partialEmpties =
      listOf(Reference("a", "", ""), Reference("", "b", ""), Reference("", "", "c"))
    for (ref in partialEmpties) {
      assertTrue(ref.isNotEmpty())
      assertFalse(ref.isEmpty())
    }
  }

  @Test
  @Suppress("detekt:LongMethod")
  fun bad() {
    data class Invalid(val string: String, val reference: Reference, val message: String)

    // adapted from https://github.com/containers/image/blob/main/docker/reference/reference_test.go
    val testCases =
      listOf(
        Invalid("", Reference("", "", ""), "registry cannot be empty"),
        Invalid(":justtag", Reference("", "", "justtag"), "registry cannot be empty"),
        Invalid(
          "@sha256:${"f".repeat(64)}",
          Reference("", "", "@sha256:${"f".repeat(64)}"),
          "registry cannot be empty",
        ),
        Invalid(
          "docker.io/validname@invaliddigest:${"f".repeat(128)}",
          Reference("docker.io", "validname", "invaliddigest:${"f".repeat(128)}"),
          "invaliddigest is not one of the registered algorithms",
        ),
        Invalid(
          "docker.io/validname@sha256:${"f".repeat(63)}",
          Reference("docker.io", "validname", "sha256:${"f".repeat(63)}"),
          "sha256 algorithm specified but hex length is not 64",
        ),
        Invalid(
          "docker.io/validname@sha512:${"f".repeat(127)}",
          Reference("docker.io", "validname", "sha512:${"f".repeat(127)}"),
          "sha512 algorithm specified but hex length is not 128",
        ),
        Invalid(
          "docker.io/Uppercase:tag",
          Reference("docker.io", "Uppercase", "tag"),
          "invalid repository",
        ),
        Invalid(
          "docker.io/${"a/".repeat(120)}",
          Reference("docker.io", "a/".repeat(120), ""),
          "invalid repository",
        ),
      )

    for (tc in testCases) {
      assertFailsWith<IllegalArgumentException> { Reference.parse(tc.string).getOrThrow() }
        .also { assertEquals(tc.message, it.message) }
      assertFailsWith<IllegalArgumentException> { tc.reference.validate() }
        .also { assertEquals(tc.message, it.message) }
    }
  }
}
