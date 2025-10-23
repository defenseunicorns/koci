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

fun createKociLogger(logLevel: KociLogLevel, tag: String) =
  Logger(
    config =
      loggerConfigInit(
        platformLogWriter(messageStringFormatter = DefaultFormatter),
        minSeverity = Severity.Warn,
      ),
    tag = "Koci$tag",
  )

enum class KociLogLevel {
  VERBOSE,
  DEBUG,
  INFO,
  WARN,
  ERROR,
  ASSERT,
}

internal fun KociLogLevel.logLevelToSeverity(): Severity =
  when (this) {
    KociLogLevel.VERBOSE -> Severity.Verbose
    KociLogLevel.DEBUG -> Severity.Debug
    KociLogLevel.INFO -> Severity.Info
    KociLogLevel.WARN -> Severity.Warn
    KociLogLevel.ERROR -> Severity.Error
    KociLogLevel.ASSERT -> Severity.Assert
  }
