/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.AuthConfig
import com.defenseunicorns.koci.api.config.ConnectionPoolConfig
import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.api.config.RetryPolicy
import com.defenseunicorns.koci.api.config.TimeoutConfig
import com.defenseunicorns.koci.api.config.TlsConfig
import com.defenseunicorns.koci.internal.HttpWrapper
import com.defenseunicorns.koci.internal.KociLogger
import com.defenseunicorns.koci.internal.RealKociLogger
import com.defenseunicorns.koci.internal.Router
import com.defenseunicorns.koci.internal.configureTls
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Entry point for the koci library.
 *
 * Configure once with the on-disk root for the OCI layout, then call [registry] to bind to a remote
 * registry with per-call tuning (auth, TLS, timeouts, concurrency, retries).
 *
 * ```kotlin
 * val koci = Koci.create(root = "/tmp/oci-store")
 * val ghcr = koci.registry("https://ghcr.io", auth = AuthConfig.Basic(user, pass))
 * ```
 *
 * ## Lifecycle
 *
 * [Koci] owns the HTTP engine and its connection pool. Call [close] to release them; every
 * [Registry] and [Repository] derived from this instance becomes invalid afterward.
 *
 * For scoped lifetimes prefer `use`:
 * ```kotlin
 * Koci.create(root = "/tmp/oci-store").use { koci ->
 *   val registry = koci.registry("https://example.com", auth = AuthConfig.Basic(user, pass))
 *   registry.repo("myorg/myimage").pull("latest").collect { /* ... */ }
 * }
 * ```
 *
 * When provided as a DI singleton, let the container own the lifetime and do not call [close] from
 * consumers.
 */
public class Koci
private constructor(
  private val json: Json,
  private val logger: KociLogger,
  public val layout: Layout,
  private val dispatcher: Dispatcher,
  private val connectionPool: ConnectionPool,
  private val testClient: HttpClient?,
) : AutoCloseable {

  private val registryClients = CopyOnWriteArrayList<HttpClient>()

  /**
   * Returns a [Registry] bound to [url] with the given configuration.
   *
   * ```kotlin
   * val ghcr = koci.registry(
   *   url = "https://ghcr.io",
   *   auth = AuthConfig.Basic(user, pass),
   *   pull = PullConfig(concurrency = 3),
   * )
   * ```
   */
  public fun registry(
    url: String,
    auth: AuthConfig = AuthConfig.None,
    tls: TlsConfig = TlsConfig.None,
    retryPolicy: RetryPolicy = RetryPolicy.Exponential(maxRetries = 3),
    timeouts: TimeoutConfig = TimeoutConfig(),
    pull: PullConfig = PullConfig(),
    push: PushConfig = PushConfig(),
  ): Registry {
    val engineClient =
      if (testClient != null) {
        testClient
      } else {
        val baseOkHttp =
          OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(connectionPool).build()
        val okHttp =
          when (tls) {
            TlsConfig.None -> baseOkHttp
            else -> configureTls(baseOkHttp, tls)
          }
        HttpClient(OkHttp) {
          install(ContentNegotiation) { json(json) }
          install(UserAgent) { agent = "Koci" }
          engine { preconfigured = okHttp }
        }
      }

    val scopedClient =
      engineClient.config {
        install(ociAuthPlugin(auth, logger))

        install(HttpRequestRetry) {
          maxRetries = retryPolicy.maxRetries

          retryOnExceptionOrServerErrors()
          when (retryPolicy) {
            is RetryPolicy.Custom -> delayMillis { retryPolicy.compute(it).inWholeMilliseconds }
            is RetryPolicy.Exponential ->
              exponentialDelay(
                base = retryPolicy.base,
                baseDelayMs = retryPolicy.baseDelay.inWholeMilliseconds,
                maxDelayMs = retryPolicy.maxDelay.inWholeMilliseconds,
                randomizationMs = retryPolicy.randomization.inWholeMilliseconds,
              )

            is RetryPolicy.Linear ->
              constantDelay(
                millis = retryPolicy.delay.inWholeMilliseconds,
                randomizationMs = retryPolicy.randomization.inWholeMilliseconds,
              )

            RetryPolicy.None -> noRetry()
          }
        }

        install(HttpTimeout) {
          requestTimeoutMillis = timeouts.requestTimeout.inWholeMilliseconds
          connectTimeoutMillis = timeouts.connectTimeout.inWholeMilliseconds
          socketTimeoutMillis = timeouts.socketTimeout.inWholeMilliseconds
        }
      }

    registryClients.add(scopedClient)

    return Registry(
      url = url,
      push = push,
      pull = pull,
      httpWrapper = HttpWrapper(scopedClient, logger),
      router = Router(url),
      store = layout,
      json = json,
      logger = logger,
    )
  }

  /**
   * Releases the HTTP engine and its connection pool. Idempotent. Any [Registry] or [Repository]
   * derived from this instance is invalid after [close] returns.
   */
  override fun close() {
    registryClients.forEach { it.close() }
    dispatcher.executorService.shutdown()
    connectionPool.evictAll()
  }

  override fun toString(): String = "Koci(root=${layout.root})"

  public companion object {
    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
      coerceInputValues = true
    }

    /**
     * Creates a [Koci] instance backed by OkHttp with the given configuration.
     *
     * @param root On-disk root for the OCI layout (blobs, index, oci-layout marker).
     * @param fileSystem Filesystem used for layout I/O. Defaults to the OS filesystem.
     * @param dispatcher Dispatcher used for blocking I/O. Defaults to [Dispatchers.IO].
     * @param connectionPool HTTP connection pool tuning shared across all registries.
     */
    public fun create(
      root: String,
      fileSystem: FileSystem = FileSystem.SYSTEM,
      dispatcher: CoroutineDispatcher = Dispatchers.IO,
      connectionPool: ConnectionPoolConfig = ConnectionPoolConfig(),
    ): Koci {
      val logger: KociLogger = RealKociLogger()
      val layout =
        Layout(root = root.toPath(), fileSystem = fileSystem, dispatcher = dispatcher, json = json)
          .apply {
            this.logger = logger
            create()
          }
      val okHttpDispatcher =
        Dispatcher().apply { maxRequestsPerHost = connectionPool.maxConnections }
      val okHttpPool =
        ConnectionPool(
          maxIdleConnections = connectionPool.maxConnections,
          keepAliveDuration = connectionPool.keepAlive.inWholeMilliseconds,
          timeUnit = TimeUnit.MILLISECONDS,
        )
      return Koci(
        json = json,
        logger = logger,
        layout = layout,
        dispatcher = okHttpDispatcher,
        connectionPool = okHttpPool,
        testClient = null
      )
    }

    internal fun create(
      root: String,
      fileSystem: FileSystem,
      dispatcher: CoroutineDispatcher,
      httpClient: HttpClient,
      logger: KociLogger = RealKociLogger(),
    ): Koci {
      val layout =
        Layout(root = root.toPath(), fileSystem = fileSystem, dispatcher = dispatcher, json = json)
          .apply {
            this.logger = logger
            create()
          }

      return Koci(
        json = json,
        logger = logger,
        layout = layout,
        dispatcher = Dispatcher(),
        connectionPool = ConnectionPool(0, 1, TimeUnit.MILLISECONDS),
        testClient = httpClient,
      )
    }
  }
}
