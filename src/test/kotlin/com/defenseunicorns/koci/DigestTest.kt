/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress(
    "detekt:LongMethod",
    "detekt:MaxLineLength",
)
class DigestTest {
    @Test
    fun algorithms() {
        val sha256 = RegisteredAlgorithm.SHA256.hasher()
        sha256.update("a".toByteArray())
        assertEquals(
            "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            sha256.digest().joinToString("") { "%02x".format(it) }
        )
        val sha512 = RegisteredAlgorithm.SHA512.hasher()
        sha512.update("a".toByteArray())
        assertEquals(
            "1f40fc92da241694750979ee6cf582f2d5d7d28e18335de05abc54d0560e0f5302860c652bf08d560252aa5e74210546f369fbbbce8c12cfc7957b2652fe9a75",
            sha512.digest().joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun good() {
        data class TC(
            val string: String,
            val expected: Digest,
            val referrersTag: String
        )

        val testCases = listOf(
            TC(
                "sha256:455f9852cfef7d0c256adfe32e44f17b7c369e11d517ab6d22819ddbc1f89937",
                Digest(RegisteredAlgorithm.SHA256, "455f9852cfef7d0c256adfe32e44f17b7c369e11d517ab6d22819ddbc1f89937"),
                "sha256-455f9852cfef7d0c256adfe32e44f17b"
            ),
            TC(
                "sha512:378db8842a28191779916fa71d5822ef6b72441420b6c2b5eeeb60cbedcabe75fc483a31a9f52f544749b0c48e786cd325a3095dfdbce7825a58d0e4cd183562",
                Digest(
                    RegisteredAlgorithm.SHA512,
                    "378db8842a28191779916fa71d5822ef6b72441420b6c2b5eeeb60cbedcabe75fc483a31a9f52f544749b0c48e786cd325a3095dfdbce7825a58d0e4cd183562"
                ),
                "sha512-378db8842a28191779916fa71d5822ef"
            ),
            TC(
                "sha256:${"a".repeat(64)}",
                Digest(RegisteredAlgorithm.SHA256, "a".repeat(64)),
                "sha256-${"a".repeat(32)}"
            ),
            TC(
                "sha512:${"a".repeat(128)}",
                Digest(RegisteredAlgorithm.SHA512, "a".repeat(128)),
                "sha512-${"a".repeat(32)}"
            )
        )

        for (case in testCases) {
            assertEquals(case.expected, Digest(case.string))
            assertEquals(case.string, case.expected.toString())
            assertEquals(case.referrersTag, case.expected.toReferrersTag())
        }

        assertEquals(
            Digest("sha256:87428fc522803d31065e7bce3cf03fe475096631e5e07bbd7a0fde60c4cf25c7"),
            Digest(
                RegisteredAlgorithm.SHA256, byteArrayOf(
                    0x87.toByte(),
                    0x42.toByte(),
                    0x8f.toByte(),
                    0xc5.toByte(),
                    0x22.toByte(),
                    0x80.toByte(),
                    0x3d.toByte(),
                    0x31.toByte(),
                    0x06.toByte(),
                    0x5e.toByte(),
                    0x7b.toByte(),
                    0xce.toByte(),
                    0x3c.toByte(),
                    0xf0.toByte(),
                    0x3f.toByte(),
                    0xe4.toByte(),
                    0x75.toByte(),
                    0x09.toByte(),
                    0x66.toByte(),
                    0x31.toByte(),
                    0xe5.toByte(),
                    0xe0.toByte(),
                    0x7b.toByte(),
                    0xbd.toByte(),
                    0x7a.toByte(),
                    0x0f.toByte(),
                    0xde.toByte(),
                    0x60.toByte(),
                    0xc4.toByte(),
                    0xcf.toByte(),
                    0x25.toByte(),
                    0xc7.toByte()
                )
            )
        )
    }
}
