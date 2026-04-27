/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Single OCI spec error entry inside a [FailureResponse].
 *
 * Internal-only — used to enrich the log line emitted by [succeeded].
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Error Codes</a>
 */
@Serializable
internal class ActionableFailure(
  val code: ErrorCode = ErrorCode.UNKNOWN,
  val message: String,
  val detail: JsonElement? = null,
) {
  override fun toString(): String =
    "ActionableFailure(code=$code, message=$message, detail=$detail)"
}
