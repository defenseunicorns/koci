/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.config

import com.defenseunicorns.koci.api.Credential
import com.defenseunicorns.koci.api.Registry

/** Authentication strategy used against a [Registry]. */
public sealed class AuthConfig {
  /** Anonymous access. */
  public data object None : AuthConfig()

  /** HTTP Basic credentials. */
  public class Basic(public val user: String, public val pass: String) : AuthConfig() {
    override fun toString(): String = "AuthConfig.Basic(user=$user, pass=***)"
  }

  /** Pre-acquired bearer token. */
  public class Bearer(public val token: String) : AuthConfig() {
    override fun toString(): String = "AuthConfig.Bearer(token=***)"
  }

  // TODO: MOBILE-216 Stop gap until we can clean up auth
  internal fun toCredential(): Credential =
    when (this) {
      None -> Credential()
      is Basic -> Credential(username = user, password = pass)
      is Bearer -> Credential(accessToken = token)
    }
}
