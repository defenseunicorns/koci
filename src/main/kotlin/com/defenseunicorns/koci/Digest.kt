/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.MessageDigest

/**
 * Represents supported digest algorithms according to the OCI spec.
 *
 * Each algorithm provides a string representation and a way to create a [MessageDigest]
 * instance for computing digests using that algorithm.
 */
enum class RegisteredAlgorithm(private val n: String) {
    SHA256("sha256"),
    SHA512("sha512");

    override fun toString(): String {
        return this.n
    }

    /**
     * Creates a [MessageDigest] instance for this algorithm.
     */
    fun hasher(): MessageDigest {
        return when (this) {
            SHA256 -> MessageDigest.getInstance("SHA-256")
            SHA512 -> MessageDigest.getInstance("SHA-512")
        }
    }
}

/**
 * Represents a content-addressable digest as defined in the OCI spec.
 *
 * A digest consists of an algorithm identifier and a hex-encoded hash value.
 * The string representation follows the format: algorithm:hex
 * (e.g., "sha256:6c3c624b58dbbcd3c0dd82b4c53f04194d1247c6eebdaab7c610cf7d66709b3b")
 *
 * Digests are used throughout the OCI spec to uniquely identify content
 * and verify content integrity.
 */
@Serializable(with = DigestSerializer::class)
class Digest(val algorithm: RegisteredAlgorithm, val hex: String) {
    /**
     * Creates a Digest from a string in the format "algorithm:hex".
     *
     * @param content String representation of the digest
     * @throws IllegalArgumentException if the algorithm is missing or not supported
     */
    constructor(content: String) : this(
        algorithm = when (val algo = content.substringBefore(":", "")) {
            "sha256" -> RegisteredAlgorithm.SHA256
            "sha512" -> RegisteredAlgorithm.SHA512
            "" -> throw IllegalArgumentException("missing algorithm")
            else -> throw IllegalArgumentException("$algo is not one of the registered algorithms")
        },
        hex = content.substringAfter(":")
    )

    /**
     * Creates a Digest from an algorithm and raw bytes.
     *
     * @param algorithm The hash algorithm used
     * @param hex The raw bytes to be hex-encoded
     */
    constructor(algorithm: RegisteredAlgorithm, hex: ByteArray) : this(
        algorithm,
        hex.joinToString("") { "%02x".format(it) }
    )

    init {
        requireNotNull(DigestRegex.matchEntire(this.toString())) { "$this does not satisfy $DigestRegex" }

        when (algorithm) {
            RegisteredAlgorithm.SHA256 -> {
                require(hex.length == 64) { "$algorithm algorithm specified but hex length is not 64" }
            }

            RegisteredAlgorithm.SHA512 -> {
                require(hex.length == 128) { "$algorithm algorithm specified but hex length is not 128" }
            }
        }
    }

    override fun toString(): String {
        return "$algorithm:$hex"
    }

    /**
     * Compares this digest with another object for equality.
     * Two digests are equal if they have the same algorithm and hex value (case-insensitive).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Digest) return false

        if (algorithm != other.algorithm) return false
        if (hex.lowercase() != other.hex.lowercase()) return false

        return true
    }

    /**
     * Returns a hash code for this digest.
     */
    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + hex.hashCode()
        return result
    }
}

/**
 * Serializer for Digest class that converts between Digest objects and their string representation.
 */
object DigestSerializer : KSerializer<Digest> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Digest", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Digest) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Digest {
        val stringValue = decoder.decodeString()
        return Digest(stringValue)
    }
}
