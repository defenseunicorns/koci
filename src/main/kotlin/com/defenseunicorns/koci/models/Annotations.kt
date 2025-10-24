/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.models

/**
 * [Pre-defined annotations
 * keys](https://github.com/opencontainers/image-spec/blob/main/annotations.md#pre-defined-annotation-keys)
 */
const val ANNOTATION_CREATED = "org.opencontainers.image.created"
const val ANNOTATION_AUTHORS = "org.opencontainers.image.authors"
const val ANNOTATION_URL = "org.opencontainers.image.url"
const val ANNOTATION_DOCUMENTATION = "org.opencontainers.image.documentation"
const val ANNOTATION_SOURCE = "org.opencontainers.image.source"
const val ANNOTATION_VERSION = "org.opencontainers.image.version"
const val ANNOTATION_REVISION = "org.opencontainers.image.revision"
const val ANNOTATION_VENDOR = "org.opencontainers.image.vendor"
const val ANNOTATION_LICENSES = "org.opencontainers.image.licenses"
const val ANNOTATION_REF_NAME = "org.opencontainers.image.ref.name"
const val ANNOTATION_TITLE = "org.opencontainers.image.title"
const val ANNOTATION_DESCRIPTION = "org.opencontainers.image.description"
const val ANNOTATION_BASE_DIGEST = "org.opencontainers.image.base.digest"
const val ANNOTATION_BASE_NAME = "org.opencontainers.image.base.name"

val Map<String, String>.annotationCreated: String?
  get() = this[ANNOTATION_CREATED]

val Map<String, String>.annotationAuthors: String?
  get() = this[ANNOTATION_AUTHORS]

val Map<String, String>.annotationURL: String?
  get() = this[ANNOTATION_URL]

val Map<String, String>.annotationDocumentation: String?
  get() = this[ANNOTATION_DOCUMENTATION]

val Map<String, String>.annotationSource: String?
  get() = this[ANNOTATION_SOURCE]

val Map<String, String>.annotationVersion: String?
  get() = this[ANNOTATION_VERSION]

val Map<String, String>.annotationRevision: String?
  get() = this[ANNOTATION_REVISION]

val Map<String, String>.annotationVendor: String?
  get() = this[ANNOTATION_VENDOR]

val Map<String, String>.annotationLicenses: String?
  get() = this[ANNOTATION_LICENSES]

val Map<String, String>.annotationRefName: String?
  get() = this[ANNOTATION_REF_NAME]

val Map<String, String>.annotationTitle: String?
  get() = this[ANNOTATION_TITLE]

val Map<String, String>.annotationDescription: String?
  get() = this[ANNOTATION_DESCRIPTION]

val Map<String, String>.annotationBaseDigest: String?
  get() = this[ANNOTATION_BASE_DIGEST]

val Map<String, String>.annotationBaseName: String?
  get() = this[ANNOTATION_BASE_NAME]
