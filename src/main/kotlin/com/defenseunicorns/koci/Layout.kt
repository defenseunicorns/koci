/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
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
) : Target {
    companion object {
        suspend fun create(root: String): Result<Layout> = withContext(Dispatchers.IO) {
            runCatching {
                var index = Index()
                val indexLocation = "$root/index.json"

                val rootDir = File(root)
                if (!rootDir.exists()) {
                    rootDir.mkdirs()
                    File("$root/oci-layout").writeText(Json.encodeToString(LayoutMarker("1.0.0")))
                } else {
                    require(rootDir.isDirectory) { "$root must be an existing directory" }
                    // TODO: handle oci-layout version + existence checking
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

    override suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
        if (descriptor.mediaType == ManifestMediaType.toString()) {
            if (!index.manifests.contains(descriptor)) return@runCatching false
        }

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
    override suspend fun remove(descriptor: Descriptor): Result<Boolean> = runCatching {
        val file = blob(descriptor)

        val exists = withContext(Dispatchers.IO) { file.exists() }

        if (!exists) {
            return@runCatching true
        }

        if (descriptor.mediaType == ManifestMediaType.toString()) {
            checkNotNull(index.manifests.firstOrNull {
                it.digest == descriptor.digest
            }) { "manifest $descriptor not found" }

            val manifests = index.manifests.filter {
                it.digest != descriptor.digest
            }

            val allLayers = manifests.flatMap { desc ->
                val manifest: Manifest = fetch(desc).use { Json.decodeFromStream(it) }
                manifest.layers
            }.toSet()

            val manifest: Manifest = fetch(descriptor).use { Json.decodeFromStream(it) }

            remove(manifest.config).getOrThrow()

            manifest.layers.forEach { layer ->
                if (!allLayers.contains(layer)) {
                    remove(layer).getOrThrow()
                }
            }

            val deleted = withContext(Dispatchers.IO) { file.delete() }

            if (!deleted) return@runCatching false

            // there may be multiple instances of this manifest w/ different ref names
            index.manifests.removeAll {
                it.digest == descriptor.digest
            }
            withContext(Dispatchers.IO) {
                syncIndex()
            }

            return@runCatching true
        }

        withContext(Dispatchers.IO) { file.delete() }
    }

    private fun syncIndex() {
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

    suspend fun resolve(reference: String): Result<Descriptor> {
        require(reference.isNotEmpty())
        return resolve { desc ->
            desc.annotations?.annotationRefName == reference
        }
    }

    suspend fun resolve(reference: Reference): Result<Descriptor> {
        return resolve { desc ->
            desc.annotations?.annotationRefName == reference.toString()
        }
    }

    // TODO: figure out how to use TaggableContent here as well?
    //
    // NOTE: this edits an annotation on the descriptor, object equality checks will not work anymore
    // TODO: unit test tagging
    suspend fun tag(descriptor: Descriptor, reference: Reference) = runCatching {
        require(descriptor.mediaType.isNotEmpty())
        require(
            descriptor.mediaType == ManifestMediaType.toString()
                    || descriptor.mediaType == IndexMediaType.toString()
        )
        require(descriptor.size > 0)

        val copy = descriptor.copy(
            annotations = descriptor.annotations?.plus(ANNOTATION_REF_NAME to reference.toString())
                ?: mapOf(ANNOTATION_REF_NAME to reference.toString())
        )
        // untag the first manifests w/ this exact ref, there should only be one
        val prevIndex = index.manifests.indexOfFirst { it.annotations?.annotationRefName == reference.toString() }
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
}
