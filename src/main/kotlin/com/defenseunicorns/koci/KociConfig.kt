/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import co.touchlab.kermit.Severity

class KociConfig
private constructor(
  val logLevel: Severity,
  val rootPath: String,
  val blobsPath: String,
  val stagingPath: String,
  val strictChecking: Boolean,
) {

  class KociConfigBuilder(val rootPath: String) {
    var logLevel: Severity = Severity.Debug
    var blobsPath: String? = null
    var stagingPath: String? = null
    var strictChecking: Boolean = true

    /**
     * Sets the log level for Koci.
     *
     * @param level The log level to set
     */
    fun logLevel(level: KociLogLevel): KociConfigBuilder = apply {
      this.logLevel = kociLogLevelToSeverity(level)
    }

    /**
     * Sets the blob path. If this is not set, the [rootPath] will be used with the default
     * [IMAGE_BLOBS_DIR].
     *
     * @param path absolute path to blobs folder
     */
    fun blobsPath(path: String): KociConfigBuilder = apply { this.blobsPath = path }

    /**
     * Sets the staging path. If this is not set, the [rootPath] will be used with the default
     * [STAGING_DIR].
     *
     * @param path absolute path to staging folder
     */
    fun stagingPath(path: String): KociConfigBuilder = apply { this.stagingPath = path }

    /**
     * Sets whether to perform strict checking of files on disk. This is an extra check on top of
     * the OCI spec and is not required for correct operation. It is recommended to keep this on for
     * production use. It is slower but guarantees that the files on disk are valid. Defaults to
     * true.
     *
     * @param strict whether to perform strict checking
     */
    fun strictChecking(strict: Boolean): KociConfigBuilder = apply { this.strictChecking = strict }

    fun build(): KociConfig {
      return KociConfig(
        logLevel = logLevel,
        rootPath = rootPath,
        blobsPath = blobsPath ?: "$rootPath/$IMAGE_BLOBS_DIR",
        stagingPath = stagingPath ?: "$rootPath/$STAGING_DIR",
        strictChecking = strictChecking,
      )
    }
  }

  companion object {
    /**
     * IMAGE_BLOBS_DIR is the directory name containing content addressable blobs in an OCI Image
     * Layout
     */
    const val IMAGE_BLOBS_DIR = "blobs"

    /** STAGING_DIR is the directory name for staging/temporary operations */
    const val STAGING_DIR = "staging"

    fun builder(rootPath: String): KociConfigBuilder = KociConfigBuilder(rootPath)
  }
}

private fun kociLogLevelToSeverity(level: KociLogLevel): Severity =
  when (level) {
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
  ASSERT,
}
