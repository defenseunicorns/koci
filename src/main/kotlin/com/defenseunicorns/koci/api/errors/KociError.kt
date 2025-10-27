/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.errors

/**
 * Sealed interface for domain errors that can occur in OCI operations.
 *
 * These are expected failure modes, not exceptional conditions. Each error type provides structured
 * information about what went wrong.
 */
sealed interface KociError
