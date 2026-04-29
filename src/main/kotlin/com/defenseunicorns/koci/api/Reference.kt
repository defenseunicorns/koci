/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import io.ktor.http.Url
import io.ktor.http.hostWithPort
import io.ktor.http.toURI
import java.net.URI

/**
 * Represents a complete reference to an OCI artifact.
 *
 * A reference consists of three components:
 * - registry: The host and optional port of the registry (e.g., "registry.example.com:5000")
 * - repository: The repository path within the registry (e.g., "library/ubuntu")
 * - reference: Either a tag or digest (e.g., "latest" or "sha256:abc123...")
 *
 * References are used to uniquely identify and locate artifacts in OCI-compliant registries.
 */
public class Reference(
  public val registry: String,
  public val repository: String,
  public val reference: String,
) {
  /**
   * Creates a [Reference] from a [Url] registry and string repository and reference.
   *
   * Extracts the host and optional port from the URL to form the registry component.
   */
  public constructor(
    registry: Url,
    repository: String,
    reference: String,
  ) : this(
    registry =
      registry.toURI().let { uri ->
        when (uri.port) {
          -1 -> registry.host // Use host instead of uri.toString() to avoid including the scheme
          else -> registry.hostWithPort
        }
      },
    repository = repository,
    reference = reference,
  )

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
   * Returns the string representation of this reference.
   *
   * The format depends on the reference type:
   * - For digests: registry/repository@digest
   * - For tags: registry/repository:tag
   * - For empty references: registry/repository
   */
  override fun toString(): String {
    return if (reference.contains(":")) {
      "$registry/$repository@$reference" // valid form A
    } else {
      if (reference.isEmpty()) {
        "$registry/$repository" // valid form D
      } else {
        "$registry/$repository:$reference" // valid form C
      }
    }
  }

  /**
   * Converts the reference string to a [com.defenseunicorns.koci.Digest] object.
   *
   * @throws IllegalArgumentException if the reference is not a valid digest
   */
  public fun digest(): Digest {
    return Digest(reference)
  }

  /**
   * Validates that all components of the reference conform to the OCI spec.
   *
   * Checks that:
   * - Registry is a valid hostname with optional port
   * - Repository matches the required pattern
   * - Reference is either empty, a valid tag, or a valid digest
   *
   * @throws IllegalArgumentException if any component is invalid
   */
  public fun validate() {
    // validate registry
    require(registry.isNotEmpty()) { "registry cannot be empty" }
    val uri = URI("dummy://$registry")
    val hostWithPort =
      when (uri.port) {
        -1 -> uri.host
        else -> "${uri.host}:${uri.port}"
      }
    require(hostWithPort == registry) { "invalid registry" }

    // validate repository
    requireNotNull(RepositoryRegex.matchEntire(repository)) { "invalid repository" }

    // validate reference
    if (reference == "") {
      return
    }
    if (TagRegex.matchEntire(reference) != null) {
      return
    }
    Digest(reference)
  }

  /** Checks if this reference is empty (all components are empty strings). */
  public fun isEmpty(): Boolean {
    return this.registry.isEmpty() && this.reference.isEmpty() && this.repository.isEmpty()
  }

  /** Checks if this reference is not empty (at least one component is non-empty). */
  public fun isNotEmpty(): Boolean {
    return this.registry.isNotEmpty() || this.reference.isNotEmpty() || this.repository.isNotEmpty()
  }

  public companion object {
    /**
     * <--- path --------------------------------------------> | - Decode `path` <=== REPOSITORY
     * ===> <--- reference ------------------> | - Decode `reference` <=== REPOSITORY ===> @
     * <=================== digest ===> | - Valid Form A <=== REPOSITORY ===> : <!!! TAG !!!> @ <===
     * digest ===> | - Valid Form B (tag is dropped) <=== REPOSITORY ===> : <=== TAG
     * ======================> | - Valid Form C <=== REPOSITORY
     * ======================================> | - Valid Form D
     *
     * Parses a string artifact reference into a [Reference] object.
     *
     * Supports multiple reference formats as defined in the OCI spec:
     * - Form A: registry/repository@digest
     * - Form B: registry/repository:tag@digest (tag is dropped)
     * - Form C: registry/repository:tag
     * - Form D: registry/repository
     *
     * @param artifact String representation of the artifact reference
     */
    public fun parse(artifact: String): Result<Reference> = runCatching {
      val reg = artifact.substringBefore("/", "")
      require(reg.isNotEmpty()) { "registry cannot be empty" }
      val repoAndRef = artifact.substringAfter("/", "")

      val (repo, ref) =
        if (repoAndRef.contains("@")) {
          val ref = repoAndRef.substringAfter("@")
          // drop tag if it exists (valid form B)
          val repoStrippedOfTag = repoAndRef.substringBefore("@").substringBefore(":")

          repoStrippedOfTag to ref // valid form A
        } else if (repoAndRef.contains(":")) {
          val repo = repoAndRef.substringBefore(":")
          val tag = repoAndRef.substringAfter(":")
          repo to tag // valid form C
        } else {
          repoAndRef to "" // valid form D
        }

      val reference = Reference(reg, repo, ref)
      reference.validate()
      reference
    }

    /**
     * Regex pattern for validating tags according to OCI spec. Tags must start with a word
     * character followed by up to 127 word, dot, or hyphen characters.
     */
    private val TagRegex: Regex = Regex("^[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}")

    /**
     * Regex pattern for validating repository names according to OCI spec. Repository names must
     * follow a specific pattern with lowercase alphanumeric characters, separators, and optional
     * path components.
     *
     * [Reference](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests)
     */
    private val RepositoryRegex: Regex =
      Regex("^[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*(\\/[a-z0-9]+((\\.|_|__|-+)[a-z0-9]+)*)*")
  }
}
