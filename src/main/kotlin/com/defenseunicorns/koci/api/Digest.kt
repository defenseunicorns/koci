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
 * "sha256:6c3c624b58dbbcd3c0dd82b4c53f04194d1247c6eebdaab7c610cf7d66709b3b").
 *
 * The primary constructor is internal; callers must go through [Digest.parse] for untrusted input
 * or through the hash-computation convenience ctor used by internal hashing paths. [parse] returns
 * null for malformed input rather than throwing.
 */
@Serializable(with = DigestSerializer::class)
public class Digest
internal constructor(public val algorithm: RegisteredAlgorithm, public val hex: String) {
  // TODO: MOBILE-213
  internal constructor(
    algorithm: RegisteredAlgorithm,
    hex: ByteArray,
  ) : this(algorithm, hex.joinToString("") { "%02x".format(it) })

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

  public companion object {
    /**
     * Parses a content-addressable digest from its canonical `algorithm:hex` string form.
     *
     * Returns `null` if the string is missing an algorithm, names an unregistered algorithm, does
     * not match the digest regex, or has the wrong hex length for its algorithm.
     */
    public fun parse(content: String): Digest? {
      val algoPart = content.substringBefore(":", "")
      val algorithm =
        when (algoPart) {
          "sha256" -> RegisteredAlgorithm.SHA256
          "sha512" -> RegisteredAlgorithm.SHA512
          else -> return null
        }
      val hex = content.substringAfter(":")

      if (DigestRegex.matchEntire("$algorithm:$hex") == null) return null

      val expectedLength =
        when (algorithm) {
          RegisteredAlgorithm.SHA256 -> SHA256_HEX_LENGTH
          RegisteredAlgorithm.SHA512 -> SHA512_HEX_LENGTH
        }
      if (hex.length != expectedLength) return null

      return Digest(algorithm, hex)
    }

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
 *
 * Returns null on malformed wire input rather than throwing — consumers that deserialize a
 * [Descriptor] with a malformed digest see `Descriptor.digest == null` and handle it gracefully
 * (skip, log, no-op).
 */
internal object DigestSerializer : KSerializer<Digest?> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("Digest", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Digest?) {
    if (value == null) {
      encoder.encodeString("")
    } else {
      encoder.encodeString(value.toString())
    }
  }

  override fun deserialize(decoder: Decoder): Digest? {
    return Digest.parse(decoder.decodeString())
  }
}
