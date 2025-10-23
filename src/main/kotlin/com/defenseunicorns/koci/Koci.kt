/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import co.touchlab.kermit.Logger
import co.touchlab.kermit.NoTagFormatter
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import com.defenseunicorns.koci.client.Layout
import com.defenseunicorns.koci.models.errors.OCIResult

class Koci {

  private val logger: Logger
  private var layout: OCIResult<Layout>

  private constructor(config: KociConfig) {
    logger =
      Logger(
        config =
          loggerConfigInit(
            platformLogWriter(messageStringFormatter = NoTagFormatter),
            minSeverity = config.logLevel,
          ),
        tag = TAG,
      )
    layout = Layout.create(
      rootPath = config.rootPath,
      blobsPath = config.blobsPath,
      stagingPath = config.stagingPath,
      strictChecking = config.strictChecking,
    )
  }

  companion object {
    private const val TAG = "Koci"

    /**
     * Creates a new [Koci] instance with the given [config].
     *
     * @param config the configuration for the [Koci] instance
     * @return a new [Koci] instance
     */
    fun create(config: KociConfig): Koci = Koci()
  }
}
