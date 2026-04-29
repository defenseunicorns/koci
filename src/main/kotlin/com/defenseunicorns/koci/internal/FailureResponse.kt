/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import kotlinx.serialization.Serializable

/**
 * OCI Distribution Spec error response payload.
 *
 * Models the JSON body that registries return alongside a 4xx/5xx status — an `errors` array of one
 * or more [ActionableFailure] entries, each with a structured [ErrorCode], a human-readable
 * message, and optional detail JSON. Internal-only — never appears in any public return type; used
 * only to enrich the log line emitted by [succeeded] when an HTTP call fails.
 *
 * @see <a
 *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#error-codes">OCI
 *   Spec: Errors</a>
 */
@Serializable internal data class FailureResponse(val errors: List<ActionableFailure>)
