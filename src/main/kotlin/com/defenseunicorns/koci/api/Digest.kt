/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Represents supported digest algorithms according to the OCI spec.
 *
 * Each algorithm provides a string representation and a way to create a [MessageDigest] instance
 * for computing digests using that algorithm.
 */
public enum class RegisteredAlgorithm(private val n: String) {
  SHA256("sha256"),
  SHA512("sha512");

  override fun toString(): String {
    return this.n
  }

  /** Creates a [MessageDigest] instance for this algorithm. */
  public fun hasher(): MessageDigest {
    return when (this) {
      SHA256 -> MessageDigest.getInstance("SHA-256")
      SHA512 -> MessageDigest.getInstance("SHA-512")
    }
  }
}

/**
 * Represents a content-addressable digest as defined in the OCI spec.
 *
 * A digest consists of an algorithm identifier and a hex-encoded hash value. The string
 * representation follows the format: algorithm:hex (e.g.,
 * "sha256:6c3c624b58dbbcd3c0dd82b4c53f04194d1247c6eebdaab7c610cf7d66709b3b")
 *
 * Digests are used throughout the OCI spec to uniquely identify content and verify content
 * integrity.
 */
@Serializable(with = DigestSerializer::class)
public class Digest(public val algorithm: RegisteredAlgorithm, public val hex: String) {
  /**
   * Creates a [Digest] from a string in the format "algorithm:hex".
   *
   * @param content String representation of the digest
   * @throws IllegalArgumentException if the algorithm is missing or not supported
   */
  public constructor(
    content: String
  ) : this(
    algorithm =
      when (val algo = content.substringBefore(":", "")) {
        "sha256" -> RegisteredAlgorithm.SHA256
        "sha512" -> RegisteredAlgorithm.SHA512
        "" -> throw IllegalArgumentException("missing algorithm")
        // TODO: MOBILE-213
        else -> throw IllegalArgumentException("$algo is not one of the registered algorithms")
      },
    hex = content.substringAfter(":"),
  )

  /**
   * Creates a [Digest] from an algorithm and raw bytes.
   *
   * @param algorithm The hash algorithm used
   * @param hex The raw bytes to be hex-encoded
   */
  public constructor(
    algorithm: RegisteredAlgorithm,
    hex: ByteArray,
  ) : this(algorithm, hex.joinToString("") { "%02x".format(it) })

  init {
    requireNotNull(DigestRegex.matchEntire(this.toString())) {
      "$this does not satisfy $DigestRegex"
    }

    when (algorithm) {
      RegisteredAlgorithm.SHA256 -> {
        require(hex.length == SHA256_HEX_LENGTH) {
          "$algorithm algorithm specified but hex length is not $SHA256_HEX_LENGTH"
        }
      }

      RegisteredAlgorithm.SHA512 -> {
        require(hex.length == SHA512_HEX_LENGTH) {
          "$algorithm algorithm specified but hex length is not $SHA512_HEX_LENGTH"
        }
      }
    }
  }

  override fun toString(): String {
    return "$algorithm:$hex"
  }

  /**
   * Compares this digest with another object for equality. Two digests are equal if they have the
   * same algorithm and hex value (case-insensitive).
   */
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Digest) return false

    if (algorithm != other.algorithm) return false
    if (hex.lowercase() != other.hex.lowercase()) return false

    return true
  }

  /** Returns a hash code for this digest. */
  override fun hashCode(): Int {
    var result = algorithm.hashCode()
    result = 31 * result + hex.hashCode()
    return result
  }

  private companion object {
    /**
     * Regex pattern for validating digest strings according to OCI spec. Digests must be in the
     * format algorithm:hex where algorithm is a lowercase identifier and hex is a base64-encoded
     * string.
     */
    private val DigestRegex: Regex = Regex("^[a-z0-9]+(?:[.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]+$")

    private const val SHA256_HEX_LENGTH = 64
    private const val SHA512_HEX_LENGTH = 128
  }
}

/**
 * Serializer for [Digest] that converts between [Digest] objects and their string representation.
 */
internal object DigestSerializer : KSerializer<Digest> {
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
