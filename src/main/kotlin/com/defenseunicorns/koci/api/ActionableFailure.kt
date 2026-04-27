/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Represents a single error in an OCI registry error response.
 *
 * Each error contains a code identifying the error type, a message for the client, and optional
 * detail information specific to the error type.
 *
 * @property code Error code identifying the type of error
 * @property message Human-readable error message
 * @property detail Additional error-specific details (optional)
 */
@Serializable
public class ActionableFailure(
  public val code: ErrorCode = ErrorCode.UNKNOWN,
  public val message: String,
  public val detail: JsonElement? = null,
) {
  override fun toString(): String =
    "ActionableFailure(code=$code, message=$message, detail=$detail)"
}
