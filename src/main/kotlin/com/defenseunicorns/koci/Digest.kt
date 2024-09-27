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

enum class RegisteredAlgorithm(private val n: String) {
    SHA256("sha256"),
    SHA512("sha512");

    override fun toString(): String {
        return this.n
    }

    fun hasher(): MessageDigest {
        return when (this) {
            SHA256 -> MessageDigest.getInstance("SHA-256")
            SHA512 -> MessageDigest.getInstance("SHA-512")
        }
    }
}

@Serializable(with = DigestSerializer::class)
class Digest(val algorithm: RegisteredAlgorithm, val hex: String) {
    constructor(content: String) : this(
        algorithm = when (val algo = content.substringBefore(':')) {
            "sha256" -> RegisteredAlgorithm.SHA256
            "sha512" -> RegisteredAlgorithm.SHA512
            else -> throw IllegalArgumentException("$algo is not one of the registered algorithms")
        },
        hex = content.substringAfter(':')
    )

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Digest) return false

        if (algorithm != other.algorithm) return false
        if (hex.lowercase() != other.hex.lowercase()) return false

        return true
    }

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + hex.hashCode()
        return result
    }
}

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
