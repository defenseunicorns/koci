/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlin.test.assertEquals
import org.junit.Test

class DigestTest {

  private lateinit var digest: Digest

  @Test
  fun `sha256 byte array`() {
    digest =
      Digest(
        algorithm = RegisteredAlgorithm.SHA256,
        hex = RegisteredAlgorithm.SHA256.hasher().apply { update("koci".toByteArray()) }.digest(),
      )

    assertEquals(expected = SHA256_KOCI, actual = digest.hex)
  }

  @Test
  fun `sha256 string`() {
    digest = Digest(algorithm = RegisteredAlgorithm.SHA256, hex = SHA256_KOCI)

    assertEquals(expected = SHA256_KOCI, actual = digest.hex)
  }

  @Test
  fun `sha256 content`() {
    digest = Digest(content = "sha256:$SHA256_KOCI")

    assertEquals(expected = SHA256_KOCI, actual = digest.hex)
  }

  @Test
  fun `sha512 byte array`() {
    digest =
      Digest(
        algorithm = RegisteredAlgorithm.SHA512,
        hex = RegisteredAlgorithm.SHA512.hasher().apply { update("koci".toByteArray()) }.digest(),
      )

    assertEquals(expected = SHA512_KOCI, actual = digest.hex)
  }

  @Test
  fun `sha512 string`() {
    digest = Digest(algorithm = RegisteredAlgorithm.SHA512, hex = SHA512_KOCI)

    assertEquals(expected = SHA512_KOCI, actual = digest.hex)
  }

  @Test
  fun `sha512 content`() {
    digest = Digest(content = "sha512:$SHA512_KOCI")

    assertEquals(expected = SHA512_KOCI, actual = digest.hex)
  }

  companion object {
    private const val SHA256_KOCI =
      "284f1fd46193427473e848b58af75dc702c5441087e2acb52d7e78298548bcb2"
    private const val SHA512_KOCI =
      "6976c76256c39fc970d85b3724b1b235058e255ac8f6105acc7ef7b3a31b7e862308b210f0eef8bb363222fcf44267b8bc2e1bdbee7fcbfd58232a0b5e160925"
  }
}
