/*
 * Copyright 2024-2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

/**
 * Annotations is a simple map of strings.
 *
 * [Pre-defined annotations
 * keys](https://github.com/opencontainers/image-spec/blob/main/annotations.md#pre-defined-annotation-keys)
 */
public typealias Annotations = Map<String, String>

public const val ANNOTATION_CREATED: String = "org.opencontainers.image.created"
public const val ANNOTATION_AUTHORS: String = "org.opencontainers.image.authors"
public const val ANNOTATION_URL: String = "org.opencontainers.image.url"
public const val ANNOTATION_DOCUMENTATION: String = "org.opencontainers.image.documentation"
public const val ANNOTATION_SOURCE: String = "org.opencontainers.image.source"
public const val ANNOTATION_VERSION: String = "org.opencontainers.image.version"
public const val ANNOTATION_REVISION: String = "org.opencontainers.image.revision"
public const val ANNOTATION_VENDOR: String = "org.opencontainers.image.vendor"
public const val ANNOTATION_LICENSES: String = "org.opencontainers.image.licenses"
public const val ANNOTATION_REF_NAME: String = "org.opencontainers.image.ref.name"
public const val ANNOTATION_TITLE: String = "org.opencontainers.image.title"
public const val ANNOTATION_DESCRIPTION: String = "org.opencontainers.image.description"
public const val ANNOTATION_BASE_DIGEST: String = "org.opencontainers.image.base.digest"
public const val ANNOTATION_BASE_NAME: String = "org.opencontainers.image.base.name"

public val Annotations.annotationCreated: String?
  get() = this[ANNOTATION_CREATED]

public val Annotations.annotationAuthors: String?
  get() = this[ANNOTATION_AUTHORS]

public val Annotations.annotationURL: String?
  get() = this[ANNOTATION_URL]

public val Annotations.annotationDocumentation: String?
  get() = this[ANNOTATION_DOCUMENTATION]

public val Annotations.annotationSource: String?
  get() = this[ANNOTATION_SOURCE]

public val Annotations.annotationVersion: String?
  get() = this[ANNOTATION_VERSION]

public val Annotations.annotationRevision: String?
  get() = this[ANNOTATION_REVISION]

public val Annotations.annotationVendor: String?
  get() = this[ANNOTATION_VENDOR]

public val Annotations.annotationLicenses: String?
  get() = this[ANNOTATION_LICENSES]

public val Annotations.annotationRefName: String?
  get() = this[ANNOTATION_REF_NAME]

public val Annotations.annotationTitle: String?
  get() = this[ANNOTATION_TITLE]

public val Annotations.annotationDescription: String?
  get() = this[ANNOTATION_DESCRIPTION]

public val Annotations.annotationBaseDigest: String?
  get() = this[ANNOTATION_BASE_DIGEST]

public val Annotations.annotationBaseName: String?
  get() = this[ANNOTATION_BASE_NAME]
