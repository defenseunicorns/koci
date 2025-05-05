/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.http.*
import java.net.URI

private fun URLBuilder.paginate(n: Int, last: String? = null): URLBuilder = apply {
    parameters.append("n", n.toString())
    last?.let { parameters.append("last", it) }
}

class Router(registryURL: String) {
    companion object {
        private const val V2_PREFIX = "v2/"
    }
    private val base: URLBuilder = URLBuilder().takeFrom(registryURL).appendPathSegments(V2_PREFIX)

    fun base(): Url {
        return base.build()
    }

    fun catalog(): Url {
        return base.clone().appendPathSegments("_catalog").build()
    }

    fun catalog(n: Int, lastRepo: String? = null): Url {
        return base.clone().appendPathSegments("_catalog").paginate(n, lastRepo).build()
    }

    fun tags(repository: String): Url {
        return base.clone().appendPathSegments(repository, "tags", "list").build()
    }

    fun manifest(repository: String, ref: String): Url {
        return base.clone().appendPathSegments(repository, "manifests", ref).build()
    }

    fun manifest(repository: String, descriptor: Descriptor): Url {
        return manifest(repository, descriptor.digest.toString())
    }

    fun blob(repository: String, descriptor: Descriptor): Url {
        return base.clone().appendPathSegments(repository, "blobs", descriptor.digest.toString())
            .build()
    }

    fun uploads(repository: String): Url {
        // the final "" allows for a trailing /
        return base.clone().appendPathSegments(repository, "blobs", "uploads", "").build()
    }

    /**
     * [Mounting a blob from another repository](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#mounting-a-blob-from-another-repository)
     *
     * @param repository The target repository to mount the blob to
     * @param sourceRepository The source repository where the blob exists
     * @param descriptor The blob descriptor to mount
     */
    fun blobMount(repository: String, sourceRepository: String, descriptor: Descriptor): Url {
        return base.clone().appendPathSegments(repository, "blobs", "uploads", "").apply {
            parameters.append("mount", descriptor.digest.toString())
            parameters.append("from", sourceRepository)
        }.build()
    }

    /**
     * [RFC 7231](https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.2)
     */
    fun parseUploadLocation(locationHeader: String): Url {
        val uri = URI(locationHeader)
        if (uri.isAbsolute) {
            return URLBuilder().takeFrom(locationHeader).build()
        }

        return URLBuilder().takeFrom(base.build()).apply {
            encodedPath = locationHeader
        }.build()
    }
}
