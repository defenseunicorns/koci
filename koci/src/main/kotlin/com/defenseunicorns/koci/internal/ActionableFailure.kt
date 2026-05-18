/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One entry in an OCI [FailureResponse]. Carries the spec [code], a human-readable [message], and
 * an optional [detail] payload that varies by code.
 *
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">
 *   OCI Spec: Error Codes</a>
 */
@Serializable
internal data class ActionableFailure(
  val code: ErrorCode = ErrorCode.UNKNOWN,
  val message: String = "",
  val detail: JsonElement? = null,
)
