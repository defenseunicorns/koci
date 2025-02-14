/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@Serializable
data class FailureResponse(
    val errors: List<ActionableFailure>,
    @Transient
    // TODO: this really the best way to initialize?
    var status: HttpStatusCode = HttpStatusCode(0, ErrorCode.UNKNOWN.toString()),
)

// https://distribution.github.io/distribution/spec/api/#errors-2
enum class ErrorCode {
    UNKNOWN,
    BLOB_UNKNOWN,
    BLOB_UPLOAD_INVALID,
    BLOB_UPLOAD_UNKNOWN,
    DIGEST_INVALID,
    MANIFEST_BLOB_UNKNOWN,
    MANIFEST_INVALID,
    MANIFEST_UNKNOWN,
    MANIFEST_UNVERIFIED,
    NAME_INVALID,
    NAME_UNKNOWN,
    PAGINATION_NUMBER_INVALID,
    RANGE_INVALID,
    SIZE_INVALID,
    TAG_INVALID,
    UNAUTHORIZED,
    DENIED,
    UNSUPPORTED
}

@Serializable
data class ActionableFailure(
    val code: ErrorCode = ErrorCode.UNKNOWN,
    val message: String,
    val detail: JsonElement? = null,
)

sealed class OCIException(message: String) : Exception(message) {
    class ManifestNotSupported(endpoint: Url, mediaType: ContentType?) :
        OCIException("Unsupported content type returned from $endpoint: $mediaType")

    class SizeMismatch(val expected: Descriptor, val actual: Long) :
        OCIException("Size mismatch: expected (${expected.size}) got ($actual)")

    class DigestMismatch(val expected: Descriptor, val actual: Digest) :
        OCIException("Digest mismatch: expected (${expected.digest}) got ($actual)")

    class PlatformNotFound(val index: Index) :
        OCIException("in [${index.manifests.map { it.platform }.joinToString()}]")

    class UnexpectedStatus(
        val expected: HttpStatusCode,
        response: HttpResponse,
    ) :
        OCIException("Expected ($expected) got (${response.status})")

    class FromResponse(val fr: FailureResponse) :
        OCIException(fr.errors.joinToString { "${it.code}: ${it.message}" })

    class EmptyTokenReturned(val response: HttpResponse) :
        OCIException("${response.call.request.method} ${response.call.request.url}: empty token returned")

    class UnableToRemove(val descriptor: Descriptor, val reason: String) :
            OCIException("Unable to remove $descriptor: $reason")
}

/**
 * @throws OCIException.FromResponse
 */
suspend fun attemptThrow4XX(response: HttpResponse) {
    require(response.status.value in 400..499) { "Attempted to throw when status was not >=400 && <=499" }

    if (response.contentType() == ContentType.Application.Json) {
        try {
            val fr: FailureResponse = response.body()
            fr.status = response.status
            throw OCIException.FromResponse(fr)
        } catch (_: NoTransformationFoundException) {
            return
        }
    }
}
