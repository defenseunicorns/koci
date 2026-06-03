/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.internal.Regex.digestRegex
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Digest algorithms supported by koci, per the OCI spec. */
public enum class RegisteredAlgorithm(private val n: String) {
  SHA256("sha256"),
  SHA512("sha512");

  override fun toString(): String {
    return this.n
  }

  /** Returns a fresh [MessageDigest] instance for this algorithm. */
  public fun hasher(): MessageDigest {
    return when (this) {
      SHA256 -> MessageDigest.getInstance("SHA-256")
      SHA512 -> MessageDigest.getInstance("SHA-512")
    }
  }
}

/**
 * Content-addressable digest in `algorithm:hex` form, e.g.
 * `sha256:6c3c624b58dbbcd3c0dd82b4c53f04194d1247c6eebdaab7c610cf7d66709b3b`.
 *
 * Use [Digest.parse] for untrusted input; it returns `null` instead of throwing on malformed
 * values. Comparison is case-insensitive on the hex portion.
 */
@Serializable(with = DigestSerializer::class)
public class Digest
internal constructor(public val algorithm: RegisteredAlgorithm, public val hex: String) {
  // TODO: #672
  internal constructor(
    algorithm: RegisteredAlgorithm,
    hex: ByteArray,
  ) : this(algorithm, hex.joinToString("") { "%02x".format(it) })

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

  public companion object {
    /**
     * Parses a digest in `algorithm:hex` form. Returns `null` if the algorithm is unsupported, the
     * format is invalid, or the hex length doesn't match the algorithm.
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

      if (digestRegex.matchEntire("$algorithm:$hex") == null) return null

      val expectedLength =
        when (algorithm) {
          RegisteredAlgorithm.SHA256 -> SHA256_HEX_LENGTH
          RegisteredAlgorithm.SHA512 -> SHA512_HEX_LENGTH
        }
      if (hex.length != expectedLength) return null

      return Digest(algorithm, hex)
    }

    private const val SHA256_HEX_LENGTH = 64
    private const val SHA512_HEX_LENGTH = 128
  }
}

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
