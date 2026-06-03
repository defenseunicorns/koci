/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Target platform for an OCI image. */
@Serializable
public class Platform(
  /** CPU architecture, e.g. `amd64`, `arm64`, `ppc64le`. */
  public val architecture: String,
  /** Operating system, e.g. `linux`, `windows`. */
  public val os: String,
  /** Operating system version, e.g. `10.0.14393.1066` for Windows. */
  @SerialName("os.version") public val osVersion: String? = null,
  /** Required OS features, e.g. `win32k`. */
  @SerialName("os.features") public val osFeatures: List<String>? = null,
  /** CPU variant, e.g. `v7` for ARMv7 when [architecture] is `arm`. */
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
