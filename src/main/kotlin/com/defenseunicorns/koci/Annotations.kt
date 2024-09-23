/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

// https://github.com/opencontainers/image-spec/blob/main/annotations.md#pre-defined-annotation-keys
typealias Annotations = Map<String, String>

const val AnnotationCreated = "org.opencontainers.image.created"
const val AnnotationAuthors = "org.opencontainers.image.authors"
const val AnnotationURL = "org.opencontainers.image.url"
const val AnnotationDocumentation = "org.opencontainers.image.documentation"
const val AnnotationSource = "org.opencontainers.image.source"
const val AnnotationVersion = "org.opencontainers.image.version"
const val AnnotationRevision = "org.opencontainers.image.revision"
const val AnnotationVendor = "org.opencontainers.image.vendor"
const val AnnotationLicenses = "org.opencontainers.image.licenses"
const val AnnotationRefName = "org.opencontainers.image.ref.name"
const val AnnotationTitle = "org.opencontainers.image.title"
const val AnnotationDescription = "org.opencontainers.image.description"
const val AnnotationBaseDigest = "org.opencontainers.image.base.digest"
const val AnnotationBaseName = "org.opencontainers.image.base.name"

val Annotations.annotationCreated: String?
    get() = this[AnnotationCreated]

val Annotations.annotationAuthors: String?
    get() = this[AnnotationAuthors]

val Annotations.annotationURL: String?
    get() = this[AnnotationURL]

val Annotations.annotationDocumentation: String?
    get() = this[AnnotationDocumentation]

val Annotations.annotationSource: String?
    get() = this[AnnotationSource]

val Annotations.annotationVersion: String?
    get() = this[AnnotationVersion]

val Annotations.annotationRevision: String?
    get() = this[AnnotationRevision]

val Annotations.annotationVendor: String?
    get() = this[AnnotationVendor]

val Annotations.annotationLicenses: String?
    get() = this[AnnotationLicenses]

val Annotations.annotationRefName: String?
    get() = this[AnnotationRefName]

val Annotations.annotationTitle: String?
    get() = this[AnnotationTitle]

val Annotations.annotationDescription: String?
    get() = this[AnnotationDescription]

val Annotations.annotationBaseDigest: String?
    get() = this[AnnotationBaseDigest]

val Annotations.annotationBaseName: String?
    get() = this[AnnotationBaseName]