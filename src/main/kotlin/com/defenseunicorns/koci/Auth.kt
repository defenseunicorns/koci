/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.api.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.auth.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Credential contains authentication credentials used to access remote registries. */
data class Credential(
  /** Username is the name of the user for the remote registry. */
  val username: String,
  /** Password is the secret associated with the username. */
  val password: String,
  /**
   * RefreshToken is a bearer token to be sent to the authorization service for fetching access
   * tokens.
   *
   * A refresh token is often referred as an identity token.
   *
   * [Reference](https://docs.docker.com/registry/spec/auth/oauth/)
   */
  val refreshToken: String,
  /**
   * AccessToken is a bearer token to be sent to the registry.
   *
   * An access token is often referred as a registry token.
   *
   * [Reference](https://docs.docker.com/registry/spec/auth/token/)
   */
  val accessToken: String,
) {
  /** Returns `true` if all properties are empty. */
  fun isEmpty(): Boolean {
    return username.isEmpty() &&
      password.isEmpty() &&
      refreshToken.isEmpty() &&
      accessToken.isEmpty()
  }

  /** Returns `true` if any property is not empty. */
  fun isNotEmpty(): Boolean {
    return username.isNotEmpty() ||
      password.isNotEmpty() ||
      refreshToken.isNotEmpty() ||
      accessToken.isNotEmpty()
  }
}

/**
 * fetchDistributionToken fetches an access token as defined by the distribution specification. It
 * fetches anonymous tokens if no credential is provided. References:
 * - https://docs.docker.com/registry/spec/auth/jwt/
 * - https://docs.docker.com/registry/spec/auth/token/
 */
@Suppress("detekt:SpreadOperator")
private suspend fun HttpClient.fetchDistributionToken(
  realm: String,
  service: String,
  scopes: List<String>,
  username: String,
  password: String,
): String {
  val res =
    get(realm) {
      if (username.isNotEmpty() || password.isNotEmpty()) {
        basicAuth(username, password)
      }
      url {
        if (service.isNotEmpty()) {
          parameters.append("service", service)
        }
        scopes.forEach { scope -> parameters.append("scope", scope) }
      }
      attributes.appendScopes(*scopes.toTypedArray())
    }

  if (res.status != HttpStatusCode.OK) {
    throw OCIException.UnexpectedStatus(HttpStatusCode.OK, res)
  }

  // As specified in https://docs.docker.com/registry/spec/auth/token/ section
  // "Token Response Fields", the token is either in `token` or
  // `access_token`. If both present, they are identical.
  @Serializable
  data class TokenResponse(
    val token: String,
    @SerialName("access_token") val accessToken: String? = null,
  )

  val json = Json { ignoreUnknownKeys = true }
  val tokenResponse: TokenResponse = json.decodeFromString(res.body())

  if (tokenResponse.accessToken != null) {
    return tokenResponse.accessToken
  }
  if (tokenResponse.token.isNotEmpty()) {
    return tokenResponse.token
  }
  throw OCIException.EmptyTokenReturned(res)
}

/**
 * fetchOAuth2Token fetches an OAuth2 access token.
 *
 * [Reference](https://docs.docker.com/registry/spec/auth/oauth/)
 */
@Suppress("detekt:ThrowsCount", "detekt:SpreadOperator")
private suspend fun HttpClient.fetchOAuth2Token(
  realm: String,
  service: String,
  scopes: List<String>,
  cred: Credential,
): String {
  val res =
    post(realm) {
      contentType(ContentType.Application.FormUrlEncoded)
      formData {
        // little redundant, but it makes linter / IDE happier this way
        require(
          cred.refreshToken.isNotEmpty() ||
            (cred.username.isNotEmpty() && cred.password.isNotEmpty())
        ) {
          "missing username or password for bearer auth"
        }
        if (cred.refreshToken.isNotEmpty()) {
          append("grant_type", "refresh_token")
          append("refresh_token", cred.refreshToken)
        } else if (cred.username.isNotEmpty() && cred.password.isNotEmpty()) {
          append("grant_type", "password")
          append("username", cred.username)
          append("password", cred.password)
        }

        append("service", service)

        val clientID = attributes.getOrNull(clientIDKey)
        append("client_id", clientID ?: DEFAULT_CLIENT_ID)

        if (scopes.isNotEmpty()) {
          append("scope", scopes.joinToString(" "))
        }

        attributes.appendScopes(*scopes.toTypedArray())
      }
    }

  if (res.status != HttpStatusCode.OK) {
    throw OCIException.UnexpectedStatus(HttpStatusCode.OK, res)
  }

  @Serializable data class TokenResponse(@SerialName("access_token") val accessToken: String)

  val json = Json { ignoreUnknownKeys = true }
  val tokenResponse: TokenResponse = json.decodeFromString(res.body())

  if (tokenResponse.accessToken.isNotEmpty()) {
    return tokenResponse.accessToken
  }

  throw OCIException.EmptyTokenReturned(res)
}

/**
 * Configuration for the OCI Authentication Plugin.
 *
 * Provides configuration options for the Ktor client plugin that handles OCI spec compliant
 * authentication.
 *
 * @property cred Credential used for authentication with registries
 * @property forceAttemptOAuth2 Forces OAuth2 authentication flow even when refresh token is empty
 */
class OCIAuthPluginConfig {
  var cred: Credential = Credential("", "", "", "")

  // TODO: figure out what this is for and if we need it
  var forceAttemptOAuth2 = false
}

/**
 * Ktor client plugin that implements OCI spec compliant authentication.
 *
 * This plugin handles various authentication schemes including:
 * - Basic authentication with username/password
 * - Bearer token authentication
 * - OAuth2 token authentication
 *
 * It automatically handles authentication challenges, token caching, and token refresh. Implements
 * the authentication flows described in the OCI spec.
 *
 * @see <a href="https://github.com/opencontainers/tob/blob/main/proposals/wg-auth.md">OCI spec:
 *   Authentication</a>
 */
val OCIAuthPlugin =
  createClientPlugin("OCIAuthPlugin", ::OCIAuthPluginConfig) {
    val tokenCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    on(Send) { request ->
      val originalCall = proceed(request)
      originalCall.response.run { // this: HttpResponse
        if (status == HttpStatusCode.Unauthorized) {
          var scopes = emptyList<String>()
          var token: String? = null
          val registryKey = request.url.build().hostWithPort

          val authHeader =
            parseAuthorizationHeader(headers[HttpHeaders.WWWAuthenticate]!!)
              as HttpAuthHeader.Parameterized
          val requestScopes = request.attributes.getOrNull(scopesKey)

          when (authHeader.authScheme) {
            AuthScheme.Basic -> {
              val cred = pluginConfig.cred
              require(cred.isNotEmpty()) { "credential required for basic auth" }
              require(cred.username.isNotEmpty() && cred.password.isNotEmpty()) {
                "missing username or password for basic auth"
              }
              if (requestScopes != null) {
                scopes = requestScopes
              }
              request.basicAuth(cred.username, cred.password)
            }

            AuthScheme.Bearer -> {
              val realm = authHeader.parameters.first { it.name == "realm" }.value
              val service = authHeader.parameters.firstOrNull { it.name == "service" }?.value ?: ""
              val challengeScopes =
                authHeader.parameters.first { it.name == "scope" }.value.split(" ")

              scopes =
                if (requestScopes != null) {
                  cleanScopes(challengeScopes + requestScopes)
                } else {
                  cleanScopes(challengeScopes)
                }

              // attempt req w/ cached token based upon scopes
              val cachedToken = tokenCache[registryKey]?.get(scopes.joinToString(" "))
              if (cachedToken != null) {
                request.bearerAuth(cachedToken)
                val cacheAttempt = proceed(request)
                if (cacheAttempt.response.status.isSuccess()) {
                  return@on cacheAttempt
                }
              }

              val cred = pluginConfig.cred
              token =
                if (
                  cred.isEmpty() ||
                    (cred.refreshToken.isEmpty() && !pluginConfig.forceAttemptOAuth2)
                ) {
                  client.fetchDistributionToken(
                    realm,
                    service,
                    scopes,
                    cred.username,
                    cred.password,
                  )
                } else {
                  client.fetchOAuth2Token(realm, service, scopes, cred)
                }

              request.bearerAuth(token)
            }
          }
          proceed(request).also {
            if (it.response.status.isSuccess() && token != null) {
              tokenCache[registryKey]?.set(scopes.joinToString(" "), token)
            }
          }
        } else {
          originalCall
        }
      }
    }
  }
