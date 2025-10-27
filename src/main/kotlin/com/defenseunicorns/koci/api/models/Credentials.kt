/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

/** Credential contains authentication credentials used to access remote registries. */
class Credentials(
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

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Credentials) return false
    if (username != other.username) return false
    if (password != other.password) return false
    if (refreshToken != other.refreshToken) return false
    return accessToken == other.accessToken
  }

  override fun hashCode(): Int {
    var result = username.hashCode()
    result = 31 * result + password.hashCode()
    result = 31 * result + refreshToken.hashCode()
    result = 31 * result + accessToken.hashCode()
    return result
  }

  override fun toString(): String =
    "Credentials(username='$username', password='$password', refreshToken='$refreshToken', accessToken='$accessToken')"
}
