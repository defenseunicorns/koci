/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.config.TlsConfig
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

internal fun configureTls(base: OkHttpClient, tls: TlsConfig): OkHttpClient =
  base
    .newBuilder()
    .apply {
      when (tls) {
        TlsConfig.None -> Unit
        is TlsConfig.CustomCa -> {
          val tm = trustManager(tls.caPem)
          val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
          sslSocketFactory(ctx.socketFactory, tm)
        }
        is TlsConfig.Mutual -> {
          val tm = trustManager(tls.caPem)
          val kmf = keyManagerFactory(tls.certPem, tls.keyPem)
          val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(tm), null) }
          sslSocketFactory(ctx.socketFactory, tm)
        }
      }
    }
    .build()

private fun trustManager(caPem: ByteArray?): X509TrustManager {
  val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
  if (caPem == null) {
    tmf.init(null as KeyStore?)
  } else {
    val ca =
      CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(caPem))
    val ks =
      KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setCertificateEntry("ca", ca)
      }
    tmf.init(ks)
  }
  return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
}

private fun keyManagerFactory(certPem: ByteArray, keyPem: ByteArray): KeyManagerFactory {
  val cert =
    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certPem))
      as X509Certificate
  val keyDer =
    Base64.getDecoder()
      .decode(
        String(keyPem)
          .lines()
          .filter { !it.startsWith("-----") && it.isNotBlank() }
          .joinToString("")
      )
  val privateKey =
    KeyFactory.getInstance(cert.publicKey.algorithm).generatePrivate(PKCS8EncodedKeySpec(keyDer))
  val ks =
    KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setKeyEntry("client", privateKey, CharArray(0), arrayOf(cert))
    }
  return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
    init(ks, CharArray(0))
  }
}

private fun insecureTrustManager(): X509TrustManager =
  object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }
