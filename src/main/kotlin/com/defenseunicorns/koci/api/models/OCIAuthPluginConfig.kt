/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

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
  var cred: Credentials = Credentials("", "", "", "")
  var forceAttemptOAuth2 = false

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is OCIAuthPluginConfig) return false
    if (cred != other.cred) return false
    return forceAttemptOAuth2 == other.forceAttemptOAuth2
  }

  override fun hashCode(): Int {
    var result = cred.hashCode()
    result = 31 * result + forceAttemptOAuth2.hashCode()
    return result
  }
}
