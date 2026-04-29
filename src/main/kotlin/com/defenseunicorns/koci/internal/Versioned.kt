/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

/**
 * Common interface for versioned OCI content types.
 *
 * Implementation detail shared by [Manifest]-like and [Index]-like content. Kept `internal` so the
 * abstraction does not leak into the public API surface.
 *
 * @property schemaVersion The schema version of the content
 */
internal sealed interface Versioned {
  val schemaVersion: Int?
}
