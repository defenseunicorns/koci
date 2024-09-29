/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import java.net.URI

val TagRegex = Regex("^\\w[\\w.-]{0,127}")
val RepositoryRegex = Regex("^[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*)*$")
val DigestRegex = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]+$")

data class Reference(
    val registry: String,
    val repository: String,
    val reference: String,
) {
    constructor(registry: Url, repository: String, reference: String) : this(
        registry = registry.toURI().let { uri ->
            when (uri.port) {
                -1 -> registry.hostWithPort
                else -> registry.host // Use host instead of uri.toString() to avoid including the scheme
            }
        }, repository = repository, reference = reference
    )

    companion object {
        //	<--- path --------------------------------------------> |  - Decode `path`
        //	<=== REPOSITORY ===> <--- reference ------------------> |    - Decode `reference`
        //	<=== REPOSITORY ===> @ <=================== digest ===> |      - Valid Form A
        //	<=== REPOSITORY ===> : <!!! TAG !!!> @ <=== digest ===> |      - Valid Form B (tag is dropped)
        //	<=== REPOSITORY ===> : <=== TAG ======================> |      - Valid Form C
        //	<=== REPOSITORY ======================================> |    - Valid Form D
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

    fun digest(): Digest {
        return Digest(reference)
    }

    // currently allowing throws
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
}