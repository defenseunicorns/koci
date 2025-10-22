package com.defenseunicorns.koci

import co.touchlab.kermit.Logger
import co.touchlab.kermit.NoTagFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter

class Koci {
}

data class KociConfig(
  val logLevel: KociLogLevel
)

class KociConfigBuilder(private val rootPath: String) {
  var logLevel: KociLogLevel = KociLogLevel.DEBUG
  var blobsPath: String? = null
}

private fun kociLogLevelToSeverity(level: KociLogLevel): Severity = when (level) {
  KociLogLevel.VERBOSE -> Severity.Verbose
  KociLogLevel.DEBUG -> Severity.Debug
  KociLogLevel.INFO -> Severity.Info
  KociLogLevel.WARN -> Severity.Warn
  KociLogLevel.ERROR -> Severity.Error
  KociLogLevel.ASSERT -> Severity.Assert
}

enum class KociLogLevel {
  VERBOSE,
  DEBUG,
  INFO,
  WARN,
  ERROR,
  ASSERT
}
