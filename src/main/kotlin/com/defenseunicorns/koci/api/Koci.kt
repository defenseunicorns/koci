/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.AuthConfig
import com.defenseunicorns.koci.api.config.BackOffPolicy
import com.defenseunicorns.koci.api.config.ConnectionPoolConfig
import com.defenseunicorns.koci.api.config.OCIAuthPlugin
import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.api.config.TimeoutConfig
import com.defenseunicorns.koci.internal.Layout
import com.defenseunicorns.koci.internal.Router
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Entry point for the koci library.
 *
 * Holds resolved configuration and owns the platform-specific HTTP engine internally. Per-registry
 * tuning (auth, timeouts, pull, push, backoff) is supplied as named arguments to [registry].
 *
 * ```kotlin
 * val koci = Koci(
 *   root = "/tmp/oci-store",
 *   connectionPool = ConnectionPoolConfig(keepAlive = 30.seconds, maxConnections = 64),
 * )
 * ```
 *
 * ## Lifecycle
 *
 * [Koci] owns the underlying HTTP client and its connection pool. Call [close] when you are done to
 * release those resources. Every [Registry] and [Repository] derived from this instance shares the
 * same client — once [close] returns, they are all invalid and using them raises an engine-closed
 * error at the next request.
 *
 * For scoped lifetimes (tests, CLI tools, short-lived jobs), prefer `use { }`:
 * ```kotlin
 * Koci(root = "/tmp/oci-store").use { koci ->
 *   val ghcr = koci.registry("https://ghcr.io", auth = Registry.Auth.Basic(user, pass))
 *   val repo = ghcr.repo("myorg/myimage")
 *   // ...
 * } // client closed here; ghcr/repo must not be used after this point
 * ```
 *
 * ## Using with DI
 *
 * Provide [Koci] as a singleton and let the DI container own the lifetime — do **not** call [close]
 * from consumers.
 *
 * ```
 * // pseudocode
 * singleton provide Koci(root = "...")
 *
 * class SomeConsumer(inject val koci: Koci) {
 *   // use koci freely; never call koci.close()
 * }
 * ```
 *
 * @param root Root directory where koci stores OCI content on disk (blobs, index, oci-layout
 *   marker). Required because koci cannot assume a safe location on every platform.
 * @param fileSystem Filesystem used for layout I/O. Defaults to the real OS filesystem.
 * @param dispatcher Dispatcher used for blocking I/O work (filesystem, network bridging).
 * @param connectionPool HTTP connection pool tuning for the shared engine.
 */
public class Koci(
  root: String,
  fileSystem: FileSystem = FileSystem.SYSTEM,
  dispatcher: CoroutineDispatcher = Dispatchers.IO,
  connectionPool: ConnectionPoolConfig = ConnectionPoolConfig(),
) : AutoCloseable {
  internal val layout: Layout =
    Layout(root = root.toPath(), fileSystem = fileSystem, dispatcher = dispatcher)

  internal val client: HttpClient =
    HttpClient(OkHttp) {
      install(ContentNegotiation) {
        json(
          Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
          }
        )
      }

      engine {
        config {
          connectionPool(
            ConnectionPool(
              maxIdleConnections = connectionPool.maxConnections,
              keepAliveDuration = connectionPool.keepAlive.inWholeMilliseconds,
              timeUnit = TimeUnit.MILLISECONDS,
            )
          )
        }
      }

      headers {
        // https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support
        append(DOCKER_HEADER_KEY, DOCKER_HEADER_VALUE)
      }

      HttpResponseValidator {
        handleResponseExceptionWithRequest { exception, _ ->
          val clientException =
            exception as? ClientRequestException ?: return@handleResponseExceptionWithRequest
          attemptThrow4XX(clientException.response)
          return@handleResponseExceptionWithRequest
        }
      }

      expectSuccess = true
    }

  /**
   * Returns a [Registry] bound to [url].
   *
   * ```kotlin
   * val ghcr = koci.registry(
   *   url = "https://ghcr.io",
   *   auth = Registry.Auth.Basic(user, pass),
   *   pull = PullConfig(concurrency = 3),
   * )
   * ```
   */
  public fun registry(
    url: String,
    auth: AuthConfig = AuthConfig.None,
    backOffPolicy: BackOffPolicy = BackOffPolicy.Exponential(),
    timeouts: TimeoutConfig = TimeoutConfig(),
    pull: PullConfig = PullConfig(),
    push: PushConfig = PushConfig(),
  ): Registry {
    val scopedClient =
      client.config {
        install(OCIAuthPlugin) { cred = auth.toCredential() }
        install(HttpTimeout) {
          requestTimeoutMillis = timeouts.requestTimeout.inWholeMilliseconds
          connectTimeoutMillis = timeouts.connectTimeout.inWholeMilliseconds
          socketTimeoutMillis = timeouts.socketTimeout.inWholeMilliseconds
        }
      }
    return Registry(
      url = url,
      auth = auth,
      pull = pull,
      push = push,
      backOffPolicy = backOffPolicy,
      client = scopedClient,
      router = Router(url),
      store = layout,
    )
  }

  /**
   * Releases the HTTP engine and its connection pool. After this returns, every [Registry] and
   * [Repository] derived from this [Koci] is invalid. Idempotent.
   */
  override fun close() {
    client.close()
  }

  override fun toString(): String = "Koci(root=${layout.root})"

  private companion object {
    private const val DOCKER_HEADER_KEY = "Docker-Distribution-API-Version"
    private const val DOCKER_HEADER_VALUE = "registry/2.0"
  }
}
