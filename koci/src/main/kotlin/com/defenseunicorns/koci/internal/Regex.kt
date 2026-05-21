/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/** Shared regex patterns used across koci. */
internal object Regex {
  /** OCI tag: a word character followed by up to 127 word, dot, or hyphen characters. */
  val tagRegex: kotlin.text.Regex = Regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}")

  /**
   * OCI repository name: lowercase alphanumeric segments separated by `.`, `_`, `__`, or `-`,
   * optionally chained as path components.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI
   *   Distribution Spec</a>
   */
  val repositoryRegex: kotlin.text.Regex =
    Regex("^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(\\/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*")

  /** OCI digest: `algorithm:hex` where algorithm is a lowercase identifier. */
  val digestRegex: kotlin.text.Regex = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]+$")

  /** Captures the next-page URL from a `Link` header. */
  val linkRegex = Regex("<(.+)>;\\s+rel=\"next\"")

  /** Captures the start and end offsets from a `Range` header in `<start>-<end>` form. */
  val uploadRangeRegex = Regex("^([0-9]+)-([0-9]+)$")
}
