/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

/**
 * TLS configuration for a registry connection. Certificates and keys are supplied as PEM bytes;
 * private keys must be PKCS#8 encoded (`-----BEGIN PRIVATE KEY-----`).
 */
public sealed class TlsConfig {
  /** Standard TLS using the system trust store. */
  public data object None : TlsConfig()

  /** Trust a specific CA certificate. Use for self-signed or internal CA registries. */
  public class CustomCa(public val caPem: ByteArray) : TlsConfig()

  /** Mutual TLS. Presents a client certificate, optionally trusting a custom CA. */
  public class Mutual(
    public val certPem: ByteArray,
    public val keyPem: ByteArray,
    public val caPem: ByteArray? = null,
  ) : TlsConfig()
}
