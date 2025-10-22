/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

/**
 * Annotations is a simple map of strings.
 *
 * [Pre-defined annotations
 * keys](https://github.com/opencontainers/image-spec/blob/main/annotations.md#pre-defined-annotation-keys)
 */
typealias Annotations = Map<String, String>

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

val Annotations.annotationCreated: String?
  get() = this[ANNOTATION_CREATED]

val Annotations.annotationAuthors: String?
  get() = this[ANNOTATION_AUTHORS]

val Annotations.annotationURL: String?
  get() = this[ANNOTATION_URL]

val Annotations.annotationDocumentation: String?
  get() = this[ANNOTATION_DOCUMENTATION]

val Annotations.annotationSource: String?
  get() = this[ANNOTATION_SOURCE]

val Annotations.annotationVersion: String?
  get() = this[ANNOTATION_VERSION]

val Annotations.annotationRevision: String?
  get() = this[ANNOTATION_REVISION]

val Annotations.annotationVendor: String?
  get() = this[ANNOTATION_VENDOR]

val Annotations.annotationLicenses: String?
  get() = this[ANNOTATION_LICENSES]

val Annotations.annotationRefName: String?
  get() = this[ANNOTATION_REF_NAME]

val Annotations.annotationTitle: String?
  get() = this[ANNOTATION_TITLE]

val Annotations.annotationDescription: String?
  get() = this[ANNOTATION_DESCRIPTION]

val Annotations.annotationBaseDigest: String?
  get() = this[ANNOTATION_BASE_DIGEST]

val Annotations.annotationBaseName: String?
  get() = this[ANNOTATION_BASE_NAME]
