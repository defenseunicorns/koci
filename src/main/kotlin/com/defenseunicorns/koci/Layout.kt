/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Suppress("detekt:TooManyFunctions")
class Layout private constructor(
    internal val index: Index,
    private val root: String,
) {
    companion object {
        suspend fun create(root: String): Result<Layout> = withContext(Dispatchers.IO) {
            runCatching {
                var index = Index()
                val indexLocation = "$root/index.json"
                val layoutFileLocation = "$root/oci-layout"

                val rootDir = File(root)
                if (!rootDir.exists()) {
                    rootDir.mkdirs()
                    File(layoutFileLocation).writeText(Json.encodeToString(LayoutMarker("1.0.0")))
                } else {
                    require(rootDir.isDirectory) { "$root must be an existing directory" }
                    // TODO: handle oci-layout version checking
                    if (!File(layoutFileLocation).exists()) {
                        File(layoutFileLocation).writeText(Json.encodeToString(LayoutMarker("1.0.0")))
                    }
                }

                if (File(indexLocation).exists()) {
                    index = Json.decodeFromString(File(indexLocation).readText())
                }

                // TODO: do this for all supported algorithms
                File("$root/blobs/sha256").mkdirs()
                File("$root/blobs/sha512").mkdirs()

                Layout(index, root)
            }
        }
    }

    suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
        val file = blob(descriptor)

        val exists = withContext(Dispatchers.IO) {
            file.exists()
        }
        if (!exists) {
            return@runCatching false
        }

        val length = withContext(Dispatchers.IO) {
            file.length()
        }
        if (length != descriptor.size) {
            throw OCIException.SizeMismatch(descriptor, length)
        }

        val digest = withContext(Dispatchers.IO) {
            file.inputStream().use { s ->
                val buffer = ByteArray(1024)
                val md = descriptor.digest.algorithm.hasher()
                var bytesRead: Int
                while (s.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
                Digest(descriptor.digest.algorithm, md.digest())
            }
        }
        if (digest != descriptor.digest) {
            throw OCIException.DigestMismatch(descriptor, digest)
        }

        true
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun expand(descriptors: List<Descriptor>): Set<Descriptor> {
        return descriptors.flatMap { desc ->
            when (desc.mediaType) {
                INDEX_MEDIA_TYPE -> {
                    if (withContext(Dispatchers.IO) { blob(desc).exists() }) {
                        val i: Index = fetch(desc).use { Json.decodeFromStream(it) }
                        listOf(desc) + i.manifests + expand(i.manifests)
                    } else {
                        emptyList()
                    }
                }

                MANIFEST_MEDIA_TYPE -> {
                    if (withContext(Dispatchers.IO) { blob(desc).exists() }) {
                        fetch(desc).use {
                            val m: Manifest = Json.decodeFromStream(it)
                            listOf(desc, m.config) + expand(m.layers)
                        }
                    } else {
                        emptyList()
                    }
                }

                else -> listOf(desc)
            }
        }.toSet()
    }

    suspend fun remove(reference: Reference): Result<Boolean> =
        resolve(reference)
            .map { descriptor ->
                remove(descriptor).getOrThrow()
            }

    // TODO: ensure removals do not impact other images through unit tests
    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
    suspend fun remove(descriptor: Descriptor): Result<Boolean> = runCatching {
        val file = blob(descriptor)

        val exists = withContext(Dispatchers.IO) { file.exists() }

        if (!exists) {
            return@runCatching true
        }

        return when (descriptor.mediaType) {
            INDEX_MEDIA_TYPE -> {
                val indexToRemove: Index = fetch(descriptor).use { Json.decodeFromStream(it) }

                index.manifests.removeAll {
                    it.digest == descriptor.digest
                }

                withContext(Dispatchers.IO) {
                    syncIndex()
                }

                for (manifestDesc in indexToRemove.manifests) {
                    remove(manifestDesc).getOrThrow()
                }

                val deleted = withContext(Dispatchers.IO) { file.delete() }

                Result.success(deleted)
            }

            MANIFEST_MEDIA_TYPE -> {
                val manifests = index.manifests.filter {
                    it.digest != descriptor.digest
                }

                index.manifests.removeAll {
                    it.digest == descriptor.digest
                }

                withContext(Dispatchers.IO) {
                    syncIndex()
                }

                val allOtherLayers = expand(manifests)

                if (allOtherLayers.contains(descriptor)) {
                    throw OCIException.UnableToRemove(descriptor, "manifest is referenced by another artifact")
                }

                val manifest: Manifest = fetch(descriptor).use { Json.decodeFromStream(it) }

                (manifest.layers + manifest.config).forEach { layer ->
                    if (!allOtherLayers.contains(layer)) {
                        remove(layer).getOrThrow()
                    }
                }

                val deleted = withContext(Dispatchers.IO) { file.delete() }

                Result.success(deleted)
            }

            else -> {
                val ret = withContext(Dispatchers.IO) { file.delete() }
                Result.success(ret)
            }
        }
    }

    internal fun syncIndex() {
        File("$root/index.json").writeText(Json.encodeToString(index))
    }

    private fun blob(descriptor: Descriptor): File {
        return File("$root/blobs/${descriptor.digest.algorithm}/${descriptor.digest.hex}")
    }

    private val pushing = ConcurrentHashMap<Descriptor, Mutex>()

    fun push(descriptor: Descriptor, stream: ByteReadChannel): Flow<Int> = channelFlow {
        val mu = pushing.computeIfAbsent(descriptor) { Mutex() }

        mu.withLock {
            try {
                val ok = exists(descriptor).getOrDefault(false)

                if (!ok) {
                    val file = blob(descriptor)
                    val md = descriptor.digest.algorithm.hasher()
                    // If resuming a download, start calculating the SHA from the data on disk
                    //
                    // it is up to the caller to properly resume the stream at the proper location,
                    // otherwise a DigestMismatch will occur
                    val fileExists = withContext(Dispatchers.IO) { file.exists() }
                    if (fileExists) {
                        withContext(Dispatchers.IO) {
                            file.inputStream().use { fis ->
                                val buffer = ByteArray(32 * 1024)
                                var bytesRead: Int
                                while (fis.read(buffer).also { bytesRead = it } != -1) {
                                    md.update(buffer, 0, bytesRead)
                                    // Not emitting bytes read from existing file
                                }
                            }
                        }
                    }

                    val buffer = ByteArray(4 * 1024)
                    var bytesRead: Int
                    withContext(Dispatchers.IO) {
                        FileOutputStream(file, true).use { out ->
                            while (stream.readAvailable(buffer).also { bytesRead = it } != -1) {
                                md.update(buffer, 0, bytesRead)
                                out.write(buffer, 0, bytesRead)

                                send(bytesRead)
                                yield()
                            }
                        }
                    }

                    val length = withContext(Dispatchers.IO) { file.length() }
                    if (length != descriptor.size) {
                        throw OCIException.SizeMismatch(descriptor, file.length())
                    }

                    val digest = Digest(descriptor.digest.algorithm, md.digest())

                    if (digest != descriptor.digest) {
                        withContext(Dispatchers.IO) {
                            file.delete()
                        }
                        throw OCIException.DigestMismatch(descriptor, digest)
                    }
                }
            } finally {
                pushing.remove(descriptor, mu)
            }
        }
    }

    suspend fun fetch(descriptor: Descriptor): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(blob(descriptor))
    }

    suspend fun resolve(predicate: suspend (Descriptor) -> Boolean): Result<Descriptor> = runCatching {
        index.manifests.first {
            predicate(it)
        }
    }

    /**
     * Resolve via [Reference] and an optional platformResolver
     *
     * Returns the _first_ match
     */
    suspend fun resolve(reference: Reference, platformResolver: ((Platform) -> Boolean)? = null): Result<Descriptor> {
        return resolve { desc ->
            val refMatches = desc.annotations?.annotationRefName == reference.toString()
            val platformMatches = if (platformResolver != null && desc.platform != null) {
                platformResolver(desc.platform)
            } else {
                true
            }
            refMatches && platformMatches
        }
    }

    // TODO: figure out how to use sealed Versioned here as well?
    //
    // NOTE: this edits an annotation on the descriptor, object equality checks will not work anymore
    // TODO: unit test tagging
    suspend fun tag(descriptor: Descriptor, reference: Reference) = runCatching {
        require(descriptor.mediaType.isNotEmpty())
        require(
            descriptor.mediaType == MANIFEST_MEDIA_TYPE
                    || descriptor.mediaType == INDEX_MEDIA_TYPE
        )
        require(descriptor.size > 0)
        reference.validate()

        val copy = descriptor.copy(
            annotations = descriptor.annotations?.plus(ANNOTATION_REF_NAME to reference.toString())
                ?: mapOf(ANNOTATION_REF_NAME to reference.toString())
        )
        // untag the first manifests w/ this exact ref, there should only be one
        val prevIndex = index.manifests.indexOfFirst {
            it.annotations?.annotationRefName == reference.toString() && it.platform == descriptor.platform
        }
        if (prevIndex != -1) {
            val prev = index.manifests[prevIndex]
            index.manifests[prevIndex] = prev.copy(
                annotations = prev.annotations?.minus(ANNOTATION_REF_NAME)
            )
        }

        index.manifests.add(copy)
        withContext(Dispatchers.IO) {
            syncIndex()
        }
    }

    fun catalog(): List<Descriptor> {
        return index.manifests.toList()
    }

    /**
     * Prunes all layers on disk that are not referenced by any manifest or index in the Layout's index.
     *
     * This is a "stop the world" style function and MUST NOT run during any other operations.
     * It should be used to clean up zombie layers that might be left on disk if a remove operation
     * is interrupted.
     *
     * @return Result containing a list of removed layer digests or an error
     */
    suspend fun gc(): Result<List<Digest>> = runCatching {
        check(pushing.isEmpty()) { "there are downloads in progress" }

        val referencedDescriptors = expand(index.manifests).toSet()

        val blobsOnDisk = mutableListOf<Digest>()

        withContext(Dispatchers.IO) {
            val sha256Dir = File("$root/blobs/sha256")
            if (sha256Dir.exists() && sha256Dir.isDirectory) {
                sha256Dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val digest = Digest(RegisteredAlgorithm.SHA256, file.name)
                        blobsOnDisk.add(digest)
                    }
                }
            }

            val sha512Dir = File("$root/blobs/sha512")
            if (sha512Dir.exists() && sha512Dir.isDirectory) {
                sha512Dir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        val digest = Digest(RegisteredAlgorithm.SHA512, file.name)
                        blobsOnDisk.add(digest)
                    }
                }
            }
        }

        withContext(Dispatchers.IO) {
            blobsOnDisk.filter { it !in referencedDescriptors.map { desc -> desc.digest } }
                .mapNotNull { zombieDigest ->
                    val file = File("$root/blobs/${zombieDigest.algorithm}/${zombieDigest.hex}")
                    if (file.delete()) zombieDigest else null
                }
        }
    }
}
