/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import com.defenseunicorns.koci.api.Credential
import com.defenseunicorns.koci.internal.DEFAULT_CLIENT_ID
import com.defenseunicorns.koci.internal.appendScopes
import com.defenseunicorns.koci.internal.cleanScopes
import com.defenseunicorns.koci.internal.clientIDKey
import com.defenseunicorns.koci.internal.scopesKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.basicAuth
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.contentType
import io.ktor.http.hostWithPort
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

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
): String? {
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
    // TODO: #658 - Log non-200 from token endpoint
    return null
  }

  val tokenResponse =
    try {
      res.body<DistributionTokenResponse>()
    } catch (_: SerializationException) {
      // TODO: #658 Log malformed token response
      return null
    }

  if (tokenResponse.accessToken != null) return tokenResponse.accessToken
  if (tokenResponse.token.isNotEmpty()) return tokenResponse.token
  // TODO: #658 Log empty token response
  return null
}

// As specified in https://docs.docker.com/registry/spec/auth/token/ section
// "Token Response Fields", the token is either in `token` or
// `access_token`. If both present, they are identical.
@Serializable
private data class DistributionTokenResponse(
  val token: String,
  @SerialName("access_token") val accessToken: String? = null,
)

/**
 * fetchOAuth2Token fetches an OAuth2 access token.
 *
 * [Reference](https://docs.docker.com/registry/spec/auth/oauth/)
 */
@Suppress("detekt:SpreadOperator")
private suspend fun HttpClient.fetchOAuth2Token(
  realm: String,
  service: String,
  scopes: List<String>,
  cred: Credential,
): String? {
  val res =
    post(realm) {
      contentType(ContentType.Application.FormUrlEncoded)
      formData {
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
    // TODO: #658 - Log non-200 from oauth2 token endpoint
    return null
  }

  val tokenResponse =
    try {
      res.body<OAuth2TokenResponse>()
    } catch (_: SerializationException) {
      // TODO: #658 - Log malformed oauth2 token response
      return null
    }

  if (tokenResponse.accessToken.isNotEmpty()) return tokenResponse.accessToken
  // TODO: #658 - Log empty oauth2 token response
  return null
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
internal class OCIAuthPluginConfig {
  var cred: Credential = Credential("", "", "", "")

  // TODO: figure out what this is for and if we need it
  var forceAttemptOAuth2: Boolean = false
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
// TODO: #677
internal val OCIAuthPlugin: ClientPlugin<OCIAuthPluginConfig> =
  createClientPlugin("OCIAuthPlugin", ::OCIAuthPluginConfig) {
    val tokenCache = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    on(Send) { request ->
      val registryKey = request.url.build().hostWithPort
      val requestScopes = request.attributes.getOrNull(scopesKey)

      if (request.headers[HttpHeaders.Authorization] == null && requestScopes != null) {
        val scopesKey = cleanScopes(requestScopes).joinToString(" ")
        val cachedToken = tokenCache[registryKey]?.get(scopesKey)
        if (cachedToken != null) {
          request.bearerAuth(cachedToken)
        }
      }

      val originalCall = proceed(request)
      originalCall.response.run { // this: HttpResponse
        if (status == HttpStatusCode.Unauthorized) {
          var scopes = emptyList<String>()
          var token: String? = null
          val registryCache = tokenCache.computeIfAbsent(registryKey) { ConcurrentHashMap() }

          val authHeader =
            parseAuthorizationHeader(headers[HttpHeaders.WWWAuthenticate]!!)
              as HttpAuthHeader.Parameterized

          when (authHeader.authScheme) {
            AuthScheme.Basic -> {
              val cred = pluginConfig.cred
              if (requestScopes != null) {
                scopes = requestScopes
              }
              // If cred is empty or missing username/password, ktor will set an empty/invalid
              // Authorization header and the server will reject it — surfaces as 401 to the caller.
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

              token?.let { request.bearerAuth(it) }
            }
          }
          proceed(request).also {
            if (it.response.status.isSuccess() && token != null) {
              val scopesKey = cleanScopes(scopes).joinToString(" ")
              registryCache[scopesKey] = token
            }
          }
        } else {
          originalCall
        }
      }
    }
  }

@Serializable
private data class OAuth2TokenResponse(@SerialName("access_token") val accessToken: String)
