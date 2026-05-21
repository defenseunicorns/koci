/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import co.touchlab.kermit.Logger

internal interface KociLogger {
  fun verbose(message: () -> String)

  fun debug(message: () -> String)

  fun info(message: () -> String)

  fun warn(message: () -> String)

  fun error(throwable: Throwable? = null, message: () -> String)
}

internal class RealKociLogger : KociLogger {
  private val log = Logger.withTag("Koci")

  override fun verbose(message: () -> String) = log.v { message() }

  override fun debug(message: () -> String) = log.d { message() }

  override fun info(message: () -> String) = log.i { message() }

  override fun warn(message: () -> String) = log.a { message() }

  override fun error(throwable: Throwable?, message: () -> String) =
    when (throwable) {
      null -> log.e { message() }
      else -> log.e(throwable) { message() }
    }
}
