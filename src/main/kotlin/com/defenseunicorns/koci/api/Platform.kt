/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Platform describes the platform which the image in the manifest runs on. */
@Serializable
public class Platform(
  /** architecture field specifies the CPU architecture, for example `amd64` or `ppc64le`. */
  public val architecture: String,
  /** os specifies the operating system, for example `linux` or `windows`. */
  public val os: String,
  /**
   * osVersion is an optional field specifying the operating system version, for example on Windows
   * `10.0.14393.1066`.
   */
  @SerialName("os.version") public val osVersion: String? = null,
  /**
   * osFeatures is an optional field specifying an array of strings, each listing a required OS
   * feature (for example on Windows `win32k`).
   */
  @SerialName("os.features") public val osFeatures: List<String>? = null,
  /**
   * variant is an optional field specifying a variant of the CPU, for example `v7` to specify ARMv7
   * when architecture is `arm`.
   */
  public val variant: String? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Platform) return false
    return architecture == other.architecture &&
      os == other.os &&
      osVersion == other.osVersion &&
      osFeatures == other.osFeatures &&
      variant == other.variant
  }

  override fun hashCode(): Int {
    var result = architecture.hashCode()
    result = 31 * result + os.hashCode()
    result = 31 * result + (osVersion?.hashCode() ?: 0)
    result = 31 * result + (osFeatures?.hashCode() ?: 0)
    result = 31 * result + (variant?.hashCode() ?: 0)
    return result
  }

  override fun toString(): String =
    "Platform(architecture=$architecture, os=$os, osVersion=$osVersion, " +
      "osFeatures=$osFeatures, variant=$variant)"
}
