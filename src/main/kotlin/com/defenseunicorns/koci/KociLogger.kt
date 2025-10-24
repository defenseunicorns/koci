/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import co.touchlab.kermit.DefaultFormatter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import com.defenseunicorns.koci.api.KociLogLevel

internal class KociLogger(logLevel: KociLogLevel) {
  private var logger: Logger? = null

  init {
    logger = createLogger(logLevel)
  }

  private fun createLogger(logLevel: KociLogLevel): Logger? {
    return logLevelToSeverity(logLevel)?.let { severity ->
      Logger(
        config =
          loggerConfigInit(
            platformLogWriter(messageStringFormatter = DefaultFormatter),
            minSeverity = severity,
          ),
        tag = "Koci",
      )
    }
  }

  private fun logLevelToSeverity(logLevel: KociLogLevel): Severity? =
    when (logLevel) {
      KociLogLevel.VERBOSE -> Severity.Verbose
      KociLogLevel.DEBUG -> Severity.Debug
      KociLogLevel.INFO -> Severity.Info
      KociLogLevel.WARN -> Severity.Warn
      KociLogLevel.ERROR -> Severity.Error
      KociLogLevel.ASSERT -> Severity.Assert
      KociLogLevel.NONE -> null
    }

  fun v(message: String) {
    logger?.v(message)
  }

  fun d(message: String) {
    logger?.d(message)
  }

  fun i(message: String) {
    logger?.i(message)
  }

  fun e(message: String, throwable: Throwable? = null) {
    logger?.e(message, throwable)
  }
}
