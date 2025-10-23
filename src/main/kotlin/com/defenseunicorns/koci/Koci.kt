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

class Koci(config: KociConfig) {
  private val logger: Logger =
    Logger(
      config =
        loggerConfigInit(
          platformLogWriter(messageStringFormatter = NoTagFormatter),
          minSeverity = config.logLevelToSeverity(),
        ),
      tag = "Koci",
    )

  private var layout: Layout? = null

  init {
    when (config.localLayoutConfig.enabled) {
      true -> {
        val layoutCreation =
          Layout.create(
            rootPath = config.localLayoutConfig.rootPath,
            blobsPath = config.localLayoutConfig.blobsPath,
            stagingPath = config.localLayoutConfig.stagingPath,
            strictChecking = config.localLayoutConfig.strictChecking,
          )

        layoutCreation.fold(
          onErr = { logger.e { "Failed to create local layout: $it" } },
          onOk = { layout = it },
        )
      }
      false -> logger.d { "Local layout disabled, skipping creation" }
    }
  }
}
