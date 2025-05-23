/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import java.net.URI

/**
 * Regex pattern for validating tags according to OCI spec.
 * Tags must start with a word character followed by up to 127 word, dot, or hyphen characters.
 */
val TagRegex = Regex("^\\w[\\w.-]{0,127}")

/**
 * Regex pattern for validating repository names according to OCI spec.
 * Repository names must follow a specific pattern with lowercase alphanumeric characters,
 * separators, and optional path components.
 */
val RepositoryRegex = Regex("^[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*)*$")

/**
 * Regex pattern for validating digest strings according to OCI spec.
 * Digests must be in the format algorithm:hex where algorithm is a lowercase identifier
 * and hex is a base64-encoded string.
 */
val DigestRegex = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]+$")

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
data class Reference(
    val registry: String,
    val repository: String,
    val reference: String,
) {
    /**
     * Creates a Reference from a [Url] registry and string repository and reference.
     *
     * Extracts the host and optional port from the URL to form the registry component.
     */
    constructor(registry: Url, repository: String, reference: String) : this(
        registry = registry.toURI().let { uri ->
            when (uri.port) {
                -1 -> registry.host // Use host instead of uri.toString() to avoid including the scheme
                else -> registry.hostWithPort
            }
        }, repository = repository, reference = reference
    )

    companion object {
        /**
         *    <--- path --------------------------------------------> |  - Decode `path`
         *    <=== REPOSITORY ===> <--- reference ------------------> |  - Decode `reference`
         *    <=== REPOSITORY ===> @ <=================== digest ===> |  - Valid Form A
         *    <=== REPOSITORY ===> : <!!! TAG !!!> @ <=== digest ===> |  - Valid Form B (tag is dropped)
         *    <=== REPOSITORY ===> : <=== TAG ======================> |  - Valid Form C
         *    <=== REPOSITORY ======================================> |  - Valid Form D
         *
         * Parses a string artifact reference into a Reference object.
         *
         * Supports multiple reference formats as defined in the OCI spec:
         * - Form A: registry/repository@digest
         * - Form B: registry/repository:tag@digest (tag is dropped)
         * - Form C: registry/repository:tag
         * - Form D: registry/repository
         *
         * @param artifact String representation of the artifact reference
         */
        fun parse(artifact: String): Result<Reference> = runCatching {
            val reg = artifact.substringBefore("/", "")
            require(reg.isNotEmpty()) { "registry cannot be empty" }
            val repoAndRef = artifact.substringAfter("/", "")

            val (repo, ref) = if (repoAndRef.contains("@")) {
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
     * @throws IllegalArgumentException if any component is invalid
     */
    fun validate() {
        // validate registry
        require(registry.isNotEmpty()) { "registry cannot be empty" }
        val uri = URI("dummy://$registry")
        val hostWithPort = when (uri.port) {
            -1 -> uri.host
            else -> "${uri.host}:${uri.port}"
        }
        require(hostWithPort == registry) { "invalid registry" }

        // validate repository
        requireNotNull(RepositoryRegex.matchEntire(repository)) {
            "invalid repository"
        }

        // validate reference
        if (reference == "") {
            return
        }
        if (TagRegex.matchEntire(reference) != null) {
            return
        }
        Digest(reference)
    }

    /**
     * Checks if this reference is empty (all components are empty strings).
     */
    fun isEmpty(): Boolean {
        return this.registry.isEmpty() && this.reference.isEmpty() && this.repository.isEmpty()
    }

    /**
     * Checks if this reference is not empty (at least one component is non-empty).
     */
    fun isNotEmpty(): Boolean {
        return this.registry.isNotEmpty() || this.reference.isNotEmpty() || this.repository.isNotEmpty()
    }
}
