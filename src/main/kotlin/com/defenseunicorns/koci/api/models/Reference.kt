package com.defenseunicorns.koci.api.models

import com.defenseunicorns.koci.api.KociResult
import com.defenseunicorns.koci.api.errors.InvalidRegistry
import com.defenseunicorns.koci.api.errors.InvalidRepository
import com.defenseunicorns.koci.models.repositoryRegex
import com.defenseunicorns.koci.models.tagRegex
import io.ktor.http.Url
import io.ktor.http.hostWithPort
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
class Reference(val registry: String, val repository: String, val reference: String) {
  /**
   * Creates a Reference from a [io.ktor.http.Url] registry and string repository and reference.
   *
   * Extracts the host and optional port from the Url to form the registry component.
   */
  constructor(
      registry: Url,
      repository: String,
      reference: String,
  ) : this(
    registry =
      registry.let { uri ->
        when (uri.port) {
          0 -> registry.host // Use host instead of uri.toString() to avoid including the scheme
          else -> registry.hostWithPort
        }
      },
    repository = repository,
    reference = reference,
  )

  /**
   * Converts the reference string to a [Digest] object.
   *
   * @throws IllegalArgumentException if the reference is not a valid digest
   */
  fun digest(): Digest {
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
   * @return OCIResult.Ok if valid, OCIResult.Err with specific error type if invalid
   */
  fun validate(): KociResult<Boolean> {
    // Validate registry
    if (registry.isBlank()) {
      return KociResult.Companion.err(InvalidRegistry(registry, "Registry cannot be empty"))
    }

    val uri =
      try {
          URI("koci://$registry")
      } catch (e: Exception) {
        return KociResult.Companion.err(InvalidRegistry(registry, "Invalid URI format: ${e.message}"))
      }

    val hostWithPort =
      when (uri.port) {
        -1 -> uri.host
        else -> "${uri.host}:${uri.port}"
      }

    if (hostWithPort != registry) {
      return KociResult.Companion.err(
          InvalidRegistry(
              registry,
              "Registry must be a valid hostname with optional port (e.g., 'registry.example.com:5000')",
          )
      )
    }

    // Validate repository
    if (repositoryRegex.matchEntire(repository) == null) {
      return KociResult.Companion.err(
          InvalidRepository(
              repository,
              "Repository must contain only lowercase alphanumeric characters, separators (._-), and optional path components separated by /",
          )
      )
    }

    // Validate reference (tag or digest)
    if (reference.isBlank()) {
      return KociResult.Companion.ok(true)
    }

    // Check if it's a valid tag
    if (tagRegex.matchEntire(reference) != null) {
      return KociResult.Companion.ok(true)
    }

    // Check if it's a valid digest
    return Digest.validate(reference)
  }

  /** Checks if this reference is empty (all components are empty strings). */
  fun isEmpty(): Boolean {
    return this.registry.isEmpty() && this.reference.isEmpty() && this.repository.isEmpty()
  }

  /** Checks if this reference is not empty (at least one component is non-empty). */
  fun isNotEmpty(): Boolean {
    return this.registry.isNotEmpty() || this.reference.isNotEmpty() || this.repository.isNotEmpty()
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Reference) return false
    if (registry != other.registry) return false
    if (repository != other.repository) return false
    return reference == other.reference
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

  companion object {
    /**
     * <--- path --------------------------------------------> | - Decode `path`
     *
     * <=== REPOSITORY ===> <--- reference ------------------> | - Decode `reference`
     *
     * <=== REPOSITORY ===> @ <=================== digest ===> | - Valid Form A
     *
     * <=== REPOSITORY ===> : <!!! TAG !!!> @ <=== digest ===> | - Valid Form B (tag is dropped)
     *
     * <=== REPOSITORY ===> : <=== TAG ======================> | - Valid Form C
     *
     * <=== REPOSITORY ======================================> | - Valid Form D
     *
     * Parses a string into a Reference.
     *
     * Supports multiple reference formats as defined in the OCI spec:
     * - Form A: registry/repository@digest
     * - Form B: registry/repository:tag@digest (tag is dropped)
     * - Form C: registry/repository:tag
     * - Form D: registry/repository
     *
     * @param artifact String representation of the artifact reference
     */
    fun parse(artifact: String): KociResult<Reference> {
      if (artifact.isBlank()) {
        return KociResult.Companion.err(InvalidRegistry("", "Reference string cannot be empty"))
      }

      val reg = artifact.substringBefore("/", "")
      if (reg.isEmpty()) {
        return KociResult.Companion.err(
            InvalidRegistry(
                "",
                "Reference must include registry (e.g., 'registry.example.com/repo:tag')",
            )
        )
      }

      val repoAndRef = artifact.substringAfter("/", "")
      if (repoAndRef.isEmpty()) {
        return KociResult.Companion.err(
            InvalidRepository(
                "",
                "Reference must include repository after registry (e.g., 'registry.example.com/repo:tag')",
            )
        )
      }

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
      return reference.validate().map { reference }
    }
  }
}
