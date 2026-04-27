/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import io.ktor.http.ContentType
import io.ktor.http.Url

/**
 * Outcome of [Repository.resolve].
 *
 * [Resolved] carries the descriptor on success. Every other variant names a specific domain failure
 * so callers can branch with an exhaustive `when`.
 */
public sealed interface ResolveOutcome {
  /** The tag resolved to [descriptor]. */
  public data class Resolved(val descriptor: Descriptor) : ResolveOutcome

  /**
   * The tag pointed at a multi-platform index and no manifest inside [index] matched the
   * platformResolver predicate.
   */
  public data class PlatformNotFound(val index: Index) : ResolveOutcome

  /**
   * The manifest endpoint returned a content type koci does not understand (neither a single
   * manifest nor an index).
   */
  public data class ManifestNotSupported(val endpoint: Url, val mediaType: ContentType?) :
    ResolveOutcome

  /** The registry returned an OCI spec error payload (4xx with JSON body). */
  public data class RegistryError(val response: FailureResponse) : ResolveOutcome
}
