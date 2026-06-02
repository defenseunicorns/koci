/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

/** Log level for Koci. Change this to tune what gets output by the logger. */
public sealed class LogLevel {
  public data object Debug : LogLevel()

  public data object Info : LogLevel()

  public data object Warn : LogLevel()

  public data object Error : LogLevel()
}
