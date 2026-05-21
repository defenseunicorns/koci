/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import com.defenseunicorns.koci.api.LogLevel

internal interface KociLogger {
  fun debug(message: () -> String)

  fun info(message: () -> String)

  fun warn(message: () -> String)

  fun error(throwable: Throwable? = null, message: () -> String)
}

internal class RealKociLogger(logLevel: LogLevel) : KociLogger {
  private val log =
    Logger(
      config =
        loggerConfigInit(
          minSeverity =
            when (logLevel) {
              LogLevel.Debug -> Severity.Debug
              LogLevel.Info -> Severity.Info
              LogLevel.Warn -> Severity.Warn
              LogLevel.Error -> Severity.Error
            }
        ),
      tag = "Koci",
    )

  override fun debug(message: () -> String) = log.d { message() }

  override fun info(message: () -> String) = log.i { message() }

  override fun warn(message: () -> String) = log.a { message() }

  override fun error(throwable: Throwable?, message: () -> String) = log.e(throwable) { message() }
}
