/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Platform describes the platform which the image in the manifest runs on. */
@Serializable
data class Platform(
  /** architecture field specifies the CPU architecture, for example `amd64` or `ppc64le`. */
  val architecture: String,
  /** os specifies the operating system, for example `linux` or `windows`. */
  val os: String,
  /**
   * osVersion is an optional field specifying the operating system version, for example on Windows
   * `10.0.14393.1066`.
   */
  @SerialName("os.version") val osVersion: String? = null,
  /**
   * osFeatures is an optional field specifying an array of strings, each listing a required OS
   * feature (for example on Windows `win32k`).
   */
  @SerialName("os.features") val osFeatures: List<String>? = null,
  /**
   * variant is an optional field specifying a variant of the CPU, for example `v7` to specify ARMv7
   * when architecture is `arm`.
   */
  val variant: String? = null,
)
