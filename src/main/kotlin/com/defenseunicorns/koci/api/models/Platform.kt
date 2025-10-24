/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Platform describes the platform which the image in the manifest runs on. */
@Serializable
class Platform(
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
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is Platform) return false
    if (architecture != other.architecture) return false
    if (os != other.os) return false
    if (osVersion != other.osVersion) return false
    if (osFeatures != other.osFeatures) return false
    return variant == other.variant
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
    "Platform(architecture='$architecture', os='$os', osVersion='$osVersion', osFeatures=$osFeatures, variant='$variant')"
}
