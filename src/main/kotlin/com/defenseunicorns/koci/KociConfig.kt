/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import co.touchlab.kermit.Severity

data class KociConfig(
  val localLayoutConfig: LocalLayoutConfig,
  val logLevel: KociLogLevel = KociLogLevel.DEBUG,
) {
  internal fun logLevelToSeverity(): Severity =
    when (logLevel) {
      KociLogLevel.VERBOSE -> Severity.Verbose
      KociLogLevel.DEBUG -> Severity.Debug
      KociLogLevel.INFO -> Severity.Info
      KociLogLevel.WARN -> Severity.Warn
      KociLogLevel.ERROR -> Severity.Error
      KociLogLevel.ASSERT -> Severity.Assert
    }
}

/**
 * LocalLayoutConfig is a configuration for the local layout.
 *
 * @param enabled if this is false, all other configuration is ignored
 * @param rootPath the root path for the local layout
 * @param blobsPath the path to the blobs directory
 * @param stagingPath the path to the staging directory
 * @param strictChecking whether to perform strict checking of files on disk
 */
data class LocalLayoutConfig(
  val enabled: Boolean,
  val rootPath: String,
  val blobsPath: String = "$rootPath/blobs",
  val stagingPath: String = "$rootPath/staging",
  val strictChecking: Boolean = true,
)

enum class KociLogLevel {
  VERBOSE,
  DEBUG,
  INFO,
  WARN,
  ERROR,
  ASSERT,
}
