/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.AuthConfig
import com.defenseunicorns.koci.internal.KociLogger
import com.defenseunicorns.koci.internal.cleanScopes
import com.defenseunicorns.koci.internal.scopesKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.hostWithPort
import io.ktor.http.isSuccess
import io.ktor.utils.io.discard
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Fetches a bearer token from the registry's authorization service. Anonymous tokens are returned
 * when [username] and [password] are both empty.
 *
 * @see <a href="https://docs.docker.com/registry/spec/auth/jwt/">Docker: JWT token
 *   authentication</a>
 * @see <a href="https://docs.docker.com/registry/spec/auth/token/">Docker: token specification</a>
 */
@Suppress("detekt:SpreadOperator")
private suspend fun HttpClient.fetchDistributionToken(
  realm: String,
  service: String,
  scopes: List<String>,
  username: String,
  password: String,
  logger: KociLogger,
): String? {
  val res =
    get(realm) {
      if (username.isNotEmpty() || password.isNotEmpty()) {
        basicAuth(username, password)
      }
      url {
        if (service.isNotEmpty()) parameters.append("service", service)
        scopes.forEach { parameters.append("scope", it) }
      }
    }

  if (res.status != HttpStatusCode.OK) {
    logger.warn { "token endpoint returned ${res.status}" }
    return null
  }

  val tokenResponse =
    try {
      res.body<DistributionTokenResponse>()
    } catch (_: SerializationException) {
      logger.warn { "malformed token response from $realm" }
      return null
    }

  if (tokenResponse.accessToken != null) return tokenResponse.accessToken
  if (tokenResponse.token.isNotEmpty()) return tokenResponse.token
  logger.warn { "empty token response from $realm" }
  return null
}

private fun cacheKey(scopes: List<String>): String = scopes.joinToString(" ")

/**
 * Attaches an auth header before the request leaves. [AuthConfig.Bearer] always preempts with the
 * pre-acquired token. For other modes, we first try a cached bearer for this host+scope; if none,
 * and we've previously confirmed this host accepts Basic, we preempt with Basic credentials.
 * Otherwise, the request goes out unauthenticated and the challenge handler in [ociAuthPlugin]
 * takes over on 401.
 */
private fun attachAuth(
  auth: AuthConfig,
  tokenCache: ConcurrentHashMap<String, ConcurrentHashMap<String, String>>,
  basicConfirmedHosts: MutableSet<String>,
  request: HttpRequestBuilder,
) {
  if (request.headers[HttpHeaders.Authorization] != null) return
  if (auth is AuthConfig.Bearer) {
    request.bearerAuth(auth.token)
    return
  }
  val host = request.url.build().hostWithPort
  val scopes = request.attributes.getOrNull(scopesKey)
  if (scopes != null) {
    val token = tokenCache[host]?.get(cacheKey(scopes))
    if (token != null) {
      request.bearerAuth(token)
      return
    }
  }
  if (auth is AuthConfig.Basic && host in basicConfirmedHosts) {
    request.basicAuth(auth.user, auth.pass)
  }
}

/** Parses the WWW-Authenticate header on a 401. Returns null if missing or unparseable. */
private fun parseChallenge(response: HttpResponse): HttpAuthHeader.Parameterized? {
  val raw = response.headers[HttpHeaders.WWWAuthenticate] ?: return null
  return parseAuthorizationHeader(raw) as? HttpAuthHeader.Parameterized
}

/** Satisfies a Basic challenge when [auth] is [AuthConfig.Basic]. Returns false otherwise. */
private fun handleBasicChallenge(auth: AuthConfig, request: HttpRequestBuilder): Boolean {
  val basic = auth as? AuthConfig.Basic ?: return false
  request.basicAuth(basic.user, basic.pass)
  return true
}

/** Fetches a bearer token from the challenge realm and attaches it to the request. */
private suspend fun handleBearerChallenge(
  auth: AuthConfig,
  client: HttpClient,
  request: HttpRequestBuilder,
  authHeader: HttpAuthHeader.Parameterized,
  requestScopes: List<String>?,
  logger: KociLogger,
): ChallengeOutcome {
  if (auth is AuthConfig.Bearer) {
    request.bearerAuth(auth.token)
    return ChallengeOutcome(token = null, scopes = requestScopes.orEmpty())
  }

  val realm = authHeader.parameters.first { it.name == "realm" }.value
  val service = authHeader.parameters.firstOrNull { it.name == "service" }?.value ?: ""
  val challengeScopes = authHeader.parameters.first { it.name == "scope" }.value.split(" ")
  // Challenge scopes come from the server and may overlap with request scopes, so the union
  // is the one place we still need to canonicalize.
  val scopes = cleanScopes(challengeScopes + requestScopes.orEmpty())

  val (username, password) =
    when (auth) {
      is AuthConfig.Basic -> auth.user to auth.pass
      else -> "" to ""
    }
  val token = client.fetchDistributionToken(realm, service, scopes, username, password, logger)
  token?.let { request.bearerAuth(it) }
  return ChallengeOutcome(token = token, scopes = scopes)
}

/**
 * Builds a Ktor client plugin that authenticates against an OCI registry using [auth]. With
 * `AuthConfig.None` it fetches anonymous tokens on Bearer challenges. With `AuthConfig.Basic` it
 * answers Basic challenges directly and exchanges credentials for a bearer on Bearer challenges.
 * With `AuthConfig.Bearer` it attaches the supplied token to every outgoing request.
 *
 * Tokens fetched dynamically are cached per host and scope, so subsequent requests with the same
 * scope skip the 401 round-trip.
 *
 * @see <a href="https://github.com/opencontainers/tob/blob/main/proposals/wg-auth.md">OCI auth
 *   WG</a>
 */
internal fun ociAuthPlugin(auth: AuthConfig, logger: KociLogger): ClientPlugin<Unit> {
  return createClientPlugin("OCIAuthPlugin") {
    // host:port -> cache-key -> bearer token. The cache key is the scopes joined by a space;
    // scopes in the request's `scopesKey` attribute are already canonical thanks to appendScopes.
    val tokenCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    // Hosts where we've confirmed Basic auth works (server returned 2xx after a Basic challenge).
    // Subsequent requests to these hosts preempt Basic to skip the 401 round-trip. A host is
    // evicted if it ever returns a Bearer challenge, since that signals the server changed mode,
    // or we were wrong about it.
    val basicConfirmedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    on(Send) { request ->
      attachAuth(auth, tokenCache, basicConfirmedHosts, request)

      val originalCall = proceed(request)
      val response = originalCall.response
      if (response.status != HttpStatusCode.Unauthorized) return@on originalCall

      // Drain the 401 body so OkHttp can return the connection to the pool. Otherwise, the
      // keep-alive thread keeps the connection in use and the JVM stays awake.
      response.bodyAsChannel().discard()

      val authHeader = parseChallenge(response) ?: return@on originalCall
      val requestScopes = request.attributes.getOrNull(scopesKey)
      val host = request.url.build().hostWithPort

      val outcome =
        when (authHeader.authScheme) {
          AuthScheme.Basic -> {
            if (!handleBasicChallenge(auth, request)) return@on originalCall
            ChallengeOutcome(token = null, scopes = requestScopes.orEmpty())
          }

          AuthScheme.Bearer -> {
            // Server wants Bearer, so any prior assumption that Basic preempt would work for
            // this host is wrong. Drop it.
            basicConfirmedHosts.remove(host)
            handleBearerChallenge(auth, client, request, authHeader, requestScopes, logger)
          }

          else -> return@on originalCall
        }

      val retried = proceed(request)
      if (retried.response.status.isSuccess()) {
        if (outcome.token != null) {
          tokenCache.computeIfAbsent(host) { ConcurrentHashMap() }[cacheKey(outcome.scopes)] =
            outcome.token
        }
        if (authHeader.authScheme == AuthScheme.Basic && auth is AuthConfig.Basic) {
          basicConfirmedHosts.add(host)
        }
      }
      retried
    }
  }
}

// Per https://docs.docker.com/registry/spec/auth/token/ "Token Response Fields", the token is
// either in `token` or `access_token`. If both are present, they are identical.
@Serializable
private data class DistributionTokenResponse(
  val token: String,
  @SerialName("access_token") val accessToken: String? = null,
)

/** Result of resolving a 401 challenge: the token acquired (or null) and the scopes it covers. */
private data class ChallengeOutcome(val token: String?, val scopes: List<String>)
