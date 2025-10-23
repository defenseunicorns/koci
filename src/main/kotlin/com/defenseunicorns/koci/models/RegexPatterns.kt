/*
 * Copyright 2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models

/**
 * Regex pattern for validating tags according to OCI spec. Tags must start with a word character
 * followed by up to 127 word, dot, or hyphen characters.
 */
val tagRegex = Regex("^\\w[\\w.-]{0,127}")

/**
 * Regex pattern for validating repository names according to OCI spec. Repository names must follow
 * a specific pattern with lowercase alphanumeric characters, separators, and optional path
 * components.
 */
val repositoryRegex =
  Regex("^[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:[._]|__|-*)[a-z0-9]+)*)*$")

/**
 * Regex pattern for parsing link headers as specified in the OCI spec.
 */
val linkHeaderRegex = Regex("<(.+)>;\\s+rel=\"next\"")
