/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

internal object Regex {
  /**
   * Regex pattern for validating tags according to OCI spec. Tags must start with a word character
   * followed by up to 127 word, dot, or hyphen characters.
   */
  val tagRegex: kotlin.text.Regex = Regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}")

  /**
   * Regex pattern for validating repository names according to OCI spec. Repository names must
   * follow a specific pattern with lowercase alphanumeric characters, separators, and optional path
   * components.
   *
   * [Reference](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests)
   */
  val repositoryRegex: kotlin.text.Regex =
    Regex("^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(\\/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*")

  /**
   * Regex pattern for validating digest strings according to OCI spec. Digests must be in the
   * format algorithm:hex where algorithm is a lowercase identifier and hex is a base64-encoded
   * string.
   */
  val digestRegex: kotlin.text.Regex = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]+$")

  val linkRegex = Regex("<(.+)>;\\s+rel=\"next\"")

  val uploadRangeRegex = Regex("^([0-9]+)-([0-9]+)$")
}
