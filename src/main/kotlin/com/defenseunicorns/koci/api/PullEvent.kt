/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import io.ktor.http.ContentType
import io.ktor.http.Url

/**
 * Events emitted by [Repository.pull].
 *
 * [Progress] events report completion percentage (0..100). [Completed] is the terminal success
 * event. Every other variant names a specific domain failure so callers can branch on a single
 * `when` without try/catch.
 */
public sealed interface PullEvent {
  /** Pull progress for the current operation, 0..100. */
  public data class Progress(val percent: Int) : PullEvent

  /** Pull completed successfully; no more events will be emitted. */
  public data object Completed : PullEvent

  /**
   * The tag pointed at a multi-platform index and no manifest inside [index] matched the
   * platformResolver predicate.
   */
  public data class PlatformNotFound(val index: Index) : PullEvent

  /**
   * The manifest endpoint returned a content type koci does not understand (neither a single
   * manifest nor an index).
   */
  public data class ManifestNotSupported(val endpoint: Url, val mediaType: ContentType?) :
    PullEvent

  /** Fetched blob's computed digest did not match [expected]. */
  public data class DigestMismatch(val expected: Descriptor, val actual: Digest) : PullEvent

  /** Fetched blob's on-disk size did not match [expected]. */
  public data class SizeMismatch(val expected: Descriptor, val actual: Long) : PullEvent

  /** The pull appeared to complete but post-pull verification could not confirm [ref]. */
  public data class Incomplete(val ref: Reference) : PullEvent

  /** The registry returned an OCI spec error payload (4xx with JSON body). */
  public data class RegistryError(val response: FailureResponse) : PullEvent
}
