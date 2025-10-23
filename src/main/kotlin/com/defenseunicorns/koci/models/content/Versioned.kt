/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.content

/**
 * Common interface for versioned OCI content types.
 *
 * Both Manifest and Index implement this interface to provide a common way to access the schema
 * version.
 *
 * @property schemaVersion The schema version of the content
 */
sealed interface Versioned {
  val schemaVersion: Int?
}
