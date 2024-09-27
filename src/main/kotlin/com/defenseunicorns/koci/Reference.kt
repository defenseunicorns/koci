/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import java.net.URI

/*
package main

import (
	"fmt"
	"regexp"

	"github.com/distribution/reference"
)

func printKotlin(variable string, re regexp.Regexp) {
	fmt.Printf("val %s = Regex(\"%s\")\n", variable, re.String())
}

// go run main.go
func main() {
	printKotlin("DigestRegexp", *reference.DigestRegexp)
	printKotlin("DomainRegexp", *reference.DomainRegexp)
	printKotlin("NameRegexp", *reference.NameRegexp)
	printKotlin("TagRegexp", *reference.TagRegexp)
}
 */

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
            if (uri.port != -1) {
                registry.hostWithPort
            } else {
                registry.host // Use host instead of uri.toString() to avoid including the scheme
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
            val reg = artifact.substringBefore("/")
            val repoAndRef = artifact.substringAfter("/")

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
//        uri, err := url.ParseRequestURI("dummy://" + r.Registry)
//        if err != nil || uri.Host != r.Registry {
//            return fmt.Errorf("%w: invalid registry", errdef.ErrInvalidReference)
//        }
        val uri = URI("dummy://$registry")
        if (uri.host != registry) {
            throw Exception("invalid registry")
        }

        // validate repository
//        if !repositoryRegexp.MatchString(r.Repository) {
//            return fmt.Errorf("%w: invalid repository", errdef.ErrInvalidReference)
//        }
        requireNotNull(RepositoryRegex.matchEntire(repository)) {
            "invalid repository"
        }

        // validate reference
//        if r.Reference == "" {
//            return nil
//        }
//        if _, err := r.Digest(); err == nil {
//            return nil
//        }
//        if !tagRegexp.MatchString(r.Reference) {
//            return fmt.Errorf("%w: invalid tag", errdef.ErrInvalidReference)
//        }
        if (reference == "") {
            return
        }


    }
}