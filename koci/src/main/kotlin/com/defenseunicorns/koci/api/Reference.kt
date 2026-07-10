/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.internal.Regex.repositoryRegex
import com.defenseunicorns.koci.internal.Regex.tagRegex
import java.net.URI

/**
 * A complete reference to an OCI artifact, made of a registry host (`registry.example.com:5000`), a
 * repository path (`library/ubuntu`), and either a tag or digest (`latest`, `sha256:abc…`).
 */
public class Reference
internal constructor(
  public val registry: String,
  public val repository: String,
  public val reference: String,
) {

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Reference) return false
    return registry == other.registry &&
      repository == other.repository &&
      reference == other.reference
  }

  override fun hashCode(): Int {
    var result = registry.hashCode()
    result = 31 * result + repository.hashCode()
    result = 31 * result + reference.hashCode()
    return result
  }

  /**
   * Formats as `registry/repository@digest` for digests, `registry/repository:tag` for tags, or
   * `registry/repository` when [reference] is empty.
   */
  override fun toString(): String {
    return if (reference.contains(":")) {
      "$registry/$repository@$reference"
    } else {
      if (reference.isEmpty()) {
        "$registry/$repository"
      } else {
        "$registry/$repository:$reference"
      }
    }
  }

  /** Returns [reference] parsed as a [Digest], or `null` if it isn't one. */
  public fun digest(): Digest? = Digest.parse(reference)

  /** Returns `true` when the registry, repository, and reference are all valid per the OCI spec. */
  public fun validate(): Boolean {
    if (registry.isEmpty()) return false
    val uri =
      try {
        URI("dummy://$registry")
      } catch (_: Exception) {
        return false
      }
    val hostWithPort =
      when (uri.port) {
        -1 -> uri.host
        else -> "${uri.host}:${uri.port}"
      }
    if (hostWithPort != registry) return false

    if (repositoryRegex.matchEntire(repository) == null) return false

    if (reference.isEmpty()) return true
    if (tagRegex.matchEntire(reference) != null) return true
    return Digest.parse(reference) != null
  }

  /** Returns `true` when every component is empty. */
  public fun isEmpty(): Boolean {
    return this.registry.isEmpty() && this.reference.isEmpty() && this.repository.isEmpty()
  }

  /** Returns `true` when at least one component is non-empty. */
  public fun isNotEmpty(): Boolean {
    return this.registry.isNotEmpty() || this.reference.isNotEmpty() || this.repository.isNotEmpty()
  }

  public companion object {
    /**
     * Builds a [Reference] from a registry URL string, stripping default ports (80, 443) from the
     * registry name.
     */
    public fun from(registry: String, repository: String, reference: String): Reference {
      val uri = URI(registry)
      val registryName =
        @Suppress("detekt:MagicNumber")
        when (uri.port) {
          -1,
          80,
          443 -> uri.host
          else -> "${uri.host}:${uri.port}"
        }
      return Reference(registryName, repository, reference)
    }

    /**
     * Parses [artifact] in any of the canonical OCI reference forms. Returns `null` if the result
     * is not valid.
     *
     * | Form                             | Example                                            |
     * |----------------------------------|----------------------------------------------------|
     * | `registry/repository@digest`     | `ghcr.io/foo/bar@sha256:abc…`                      |
     * | `registry/repository:tag@digest` | `ghcr.io/foo/bar:latest@sha256:abc…` (tag dropped) |
     * | `registry/repository:tag`        | `ghcr.io/foo/bar:latest`                           |
     * | `registry/repository`            | `ghcr.io/foo/bar`                                  |
     */
    public fun parse(artifact: String): Reference? {
      val reg = artifact.substringBefore("/", "")
      if (reg.isEmpty()) return null
      val repoAndRef = artifact.substringAfter("/", "")

      val (repo, ref) =
        if (repoAndRef.contains("@")) {
          val ref = repoAndRef.substringAfter("@")
          val repoStrippedOfTag = repoAndRef.substringBefore("@").substringBefore(":")
          repoStrippedOfTag to ref
        } else if (repoAndRef.contains(":")) {
          val repo = repoAndRef.substringBefore(":")
          val tag = repoAndRef.substringAfter(":")
          repo to tag
        } else {
          repoAndRef to ""
        }

      val reference = Reference(reg, repo, ref)
      if (!reference.validate()) return null
      return reference
    }
  }
}
