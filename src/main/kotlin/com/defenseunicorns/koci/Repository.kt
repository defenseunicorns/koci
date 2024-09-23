/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private val systemArch = if (System.getProperty("os.arch") == "aarch64") "arm64" else "amd64"

// TODO: this must match `go tool dist list`
private val systemOS = System.getProperty("os.name").let { os ->
    when {
        os.startsWith("Mac OS X") -> "darwin"
        os.startsWith("Win") -> "windows"
        os.startsWith("Linux") -> "linux"
        else -> "unknown"
    }
}

// TODO: take into account / "discover" platform variants
val SystemPlatform = Platform(
    os = systemOS,
    architecture = systemArch
)

fun defaultResolver(platform: Platform): Boolean {
    return platform.architecture == SystemPlatform.architecture && platform.os == SystemPlatform.os
}

fun Headers.toUploadStatus(): UploadStatus {
    val location =
        this[HttpHeaders.Location] ?: throw Exception("missing Location header")
    val range = this[HttpHeaders.Range] ?: throw Exception(
        "missing Range header"
    )
    // this header MAY not exist
    val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

    // ^[0-9]+-[0-9]+$
    val totalBytes = range.split("-")[1].toLong().absoluteValue

    return UploadStatus(location, totalBytes, minChunk)
}

@Suppress("detekt:TooManyFunctions")
class Repository(
    private val client: HttpClient,
    private val router: Router,
    private val name: String,
) : Target {
    private val uploading = ConcurrentHashMap<Descriptor, UploadStatus>()

    override suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
        val endpoint = when (descriptor.mediaType) {
            ManifestMediaType.toString(),
            IndexMediaType.toString(),
                -> router.manifest(name, descriptor)

            else -> router.blob(name, descriptor)
        }
        client.head(endpoint).status.isSuccess()
    }

    /**
     * [HEAD|GET /v2/<name>/manifests/<reference>](https://distribution.github.io/distribution/spec/api/#existing-manifests)
     */
    suspend fun resolve(
        tag: String, resolver: (Platform) -> Boolean = ::defaultResolver,
    ): Result<Descriptor> = runCatching {
        val endpoint = router.manifest(name, tag)
        val response = client.head(endpoint) {
            accept(ManifestMediaType)
            accept(IndexMediaType)
        }

        when (response.contentType()) {
            IndexMediaType -> {
                val indexResponse = client.get(endpoint) {
                    accept(IndexMediaType)
                }
                val index = Json.decodeFromString<Index>(indexResponse.bodyAsText())

                try {
                    index.manifests.first { desc ->
                        desc.platform != null && resolver(desc.platform)
                    }
                } catch (e: NoSuchElementException) {
                    throw OCIException.PlatformNotFound(index)
                }
            }

            ManifestMediaType -> {
                // TODO: is is safe to transform the headers into a descriptor, or should we actually pull and create the descriptor from the returned data?
                toDescriptor(response.headers)
            }

            else -> throw OCIException.ManifestNotSupported(
                endpoint,
                response.contentType()
            )
        }
    }

    /**
     * [Deleting blobs](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-blobs)
     *
     * [Deleting manifests](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-manifests)
     *
     * Registries MAY implement deletion or they MAY disable it.
     *
     * TODO: Similarly, a registry MAY implement tag deletion, while others MAY allow deletion only by manifest.
     */
    override suspend fun remove(descriptor: Descriptor) = runCatching {
        val endpoint = when (descriptor.mediaType) {
            ManifestMediaType.toString(),
            IndexMediaType.toString(),
                -> router.manifest(name, descriptor)

            else -> router.blob(name, descriptor)
        }

        client.delete(endpoint).status.isSuccess()
    }

    suspend fun manifest(descriptor: Descriptor): Result<Manifest> = runCatching {
        require(descriptor.mediaType == ManifestMediaType.toString())
        client.get(router.manifest(name, descriptor)) {
            accept(ManifestMediaType)
        }.body()
    }

    suspend fun index(descriptor: Descriptor): Result<Index> = runCatching {
        require(descriptor.mediaType == IndexMediaType.toString())
        client.get(router.manifest(name, descriptor)) {
            accept(IndexMediaType)
        }.body()
    }

    /**
     * [GET /v2/<name>/tags/list](https://distribution.github.io/distribution/spec/api/#listing-image-tags)
     */
    suspend fun tags(): Result<TagsResponse> = runCatching {
        client.get(router.tags(name)).body()
    }

    fun pull(tag: String, store: Layout, resolver: (Platform) -> Boolean = ::defaultResolver): Flow<Int> =
        channelFlow {
            resolve(tag, resolver).map {
                pull(it, store).onCompletion { cause ->
                    if (cause == null) {
                        val ref = Reference(
                            registry = router.base(),
                            repository = name,
                            reference = tag
                        )
                        // if pull was successful, tag the resolved desc w/ the image's ref
                        store.tag(it, ref).getOrThrow()
                    }
                }.collect { progress ->
                    send(progress)
                }
            }.getOrThrow()
        }

    /**
     * [Pulling An Image](https://distribution.github.io/distribution/spec/api/#pulling-an-image)
     *
     * [GET /v2/<name>/blobs/<digest>](https://distribution.github.io/distribution/spec/api/#pulling-a-layer)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pull(descriptor: Descriptor, store: Layout): Flow<Int> = channelFlow {
        when (descriptor.mediaType) {
            IndexMediaType.toString() -> {
                val index = index(descriptor).getOrThrow()
                var acc = 0
                index.manifests.forEach { manifestDescriptor ->
                    pull(manifestDescriptor, store).collect { progress ->
                        acc += progress / index.manifests.size
                        send(acc)
                    }
                }
            }

            ManifestMediaType.toString() -> {
                if (store.exists(descriptor).getOrDefault(false)) {
                    send(100)
                    return@channelFlow
                }
                val manifest = manifest(descriptor).getOrThrow()
                val layersToFetch = manifest.layers.toMutableList()
                var totalBytes = layersToFetch.sumOf { it.size }

                if (!store.exists(manifest.config).getOrDefault(false)) {
                    layersToFetch += manifest.config
                    totalBytes += manifest.config.size
                }

                // Include the manifest itself in the progress
                totalBytes += descriptor.size

                var currentProgress = 0.0

                layersToFetch.asFlow()
                    .map { layer ->
                        flow {
                            copy(layer, store).collect { progress ->
                                currentProgress += progress
                                emit((currentProgress * 100 / totalBytes).roundToInt())
                            }
                        }
                    }
                    .flattenMerge(concurrency = 3) // TODO: figure out best API to expose concurrency settings
                    .onCompletion { cause ->
                        // if there were no errors, then we can save the manifest itself and "mark" this pull as done via saving the index
                        if (cause == null) {
                            copy(descriptor, store).collect { progress ->
                                currentProgress += progress
                                emit((currentProgress * 100 / totalBytes).roundToInt())
                            }
                        }
                    }
                    .collect { progress ->
                        send(progress)
                    }
            }

            else -> {
                copy(descriptor, store).collect { progress ->
                    send(progress)
                }
            }
        }
    }

    @Volatile
    private var supportsRange: Boolean? = null

    /**
     * This endpoint may also support RFC7233 compliant range requests.
     * Support can be detected by issuing a HEAD request.
     * If the header `Accept-Ranges: bytes` is returned, range requests
     * can be used to fetch partial content.
     */
    private suspend fun supportsRange(descriptor: Descriptor): Boolean {
        supportsRange?.let { return it }

        require(descriptor.mediaType != ManifestMediaType.toString())
        require(descriptor.mediaType != IndexMediaType.toString())

        val response = runCatching { client.head(router.blob(name, descriptor)) }.getOrNull()
        val rangeSupported = response?.headers?.get("Accept-Ranges") == "bytes"

        return synchronized(this) {
            supportsRange ?: run {
                supportsRange = rangeSupported
                rangeSupported
            }
        }
    }

    fun copy(descriptor: Descriptor, store: Layout): Flow<Int> = channelFlow {
        val ok = store.exists(descriptor)

        // if the descriptor is 100% downloaded w/ size and sha matching, early return
        if (ok.getOrDefault(false)) {
            send(descriptor.size.toInt())
            return@channelFlow
        }

        val endpoint = when (descriptor.mediaType) {
            ManifestMediaType.toString(),
            IndexMediaType.toString(),
                -> router.manifest(name, descriptor)

            else -> router.blob(name, descriptor)
        }

        client.prepareGet(endpoint) {
            when (descriptor.mediaType) {
                IndexMediaType.toString() -> {
                    accept(IndexMediaType)
                }

                ManifestMediaType.toString() -> {
                    accept(ManifestMediaType)
                }
            }

            when (val exception = ok.exceptionOrNull()) {
                is OCIException.SizeMismatch -> {
                    if (supportsRange(descriptor)) {
                        val start = exception.actual

                        // fire partial progress has happened
                        send(start.toInt())

                        headers.append("Range", "bytes=$start-${descriptor.size - 1}")
                    } else {
                        store.remove(descriptor).getOrThrow()
                    }
                }

                is OCIException.DigestMismatch -> {
                    store.remove(descriptor).getOrThrow()
                }

                null -> {
                    // this branch should never happen
                }

                else -> {
                    throw exception
                }
            }
        }.execute { response ->
            store.push(descriptor, response.body()).collect { prog ->
                send(prog)
            }
        }
    }

    private suspend fun startOrResumeUpload(descriptor: Descriptor): UploadStatus {
        return when (val prev = uploading[descriptor]) {
            null -> {
                val res = client.post(router.uploads(name)) {
                    headers[HttpHeaders.ContentLength] = 0.toString()
                }
                if (res.status != HttpStatusCode.Accepted) {
                    throw OCIException.UnexpectedStatus(HttpStatusCode.Accepted, res)
                }
                return res.headers.toUploadStatus()
            }

            else -> {
                if (prev.offset > 0) {
                    try {
                        client.get(router.parseUploadLocation(prev.location)).also { res ->
                            if (res.status != HttpStatusCode.NoContent) {
                                throw OCIException.UnexpectedStatus(HttpStatusCode.NoContent, res)
                            }

                            val curr = res.headers.toUploadStatus()
                            if (curr.offset != prev.offset) {
                                prev.offset = curr.offset
                            }
                        }
                    } catch (e: OCIException.FromResponse) {
                        if (e.fr.status == HttpStatusCode.NotFound) {
                            uploading.remove(descriptor)

                            return startOrResumeUpload(descriptor)
                        }
                        throw e
                    } catch (e: ClientRequestException) {
                        if (e.response.status == HttpStatusCode.NotFound) {
                            uploading.remove(descriptor)

                            return startOrResumeUpload(descriptor)
                        }
                        throw e
                    }
                }

                prev
            }
        }
    }

    /**
     * [Pushing blobs](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs)
     *
     * If the content being pushed is > 5MB the push will be [chunked](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-a-blob-in-chunks), otherwise performed in a [single POST](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#single-post)
     *
     * If errors occur, calling this function again will attempt to resume upload at whatever byte offset the previous attempt stopped at
     */
    fun push(stream: InputStream, expected: Descriptor): Flow<Long> =
        channelFlow {
            if (exists(expected).getOrDefault(false)) {
                send(expected.size)
                withContext(Dispatchers.IO) {
                    stream.close()
                }
                return@channelFlow
            }

            val start = startOrResumeUpload(expected).also { uploading[expected] = it }

            if (start.minChunkSize == 0L) {
                start.minChunkSize = 5 * 1024 * 1024
            }

            when (val bytesLeft = expected.size - start.offset) {
                in 1..start.minChunkSize -> {
                    client.put(start.location) {
                        url {
                            encodedParameters.append("digest", expected.digest.toString())
                        }
                        headers {
                            append(HttpHeaders.ContentLength, expected.size.toString())
                        }
                        setBody(stream)
                    }.also { res ->
                        if (res.status != HttpStatusCode.Created) {
                            throw OCIException.UnexpectedStatus(HttpStatusCode.Created, res)
                        }

                        send(bytesLeft)
                    }
                }

                else -> {
                    var offset = start.offset
                    stream.use { s ->
                        if (offset > 0) s.skipNBytes(offset + 1)

                        var chunk: ByteArray
                        while (isActive) {
                            chunk = s.readNBytes(start.minChunkSize.toInt())

                            if (chunk.isEmpty()) {
                                break
                            }

                            val endRange = offset + chunk.size - 1
                            val currentLocation = checkNotNull(uploading[expected]?.location) {
                                "upload location unexpectedly null"
                            }

                            client.patch(router.parseUploadLocation(currentLocation)) {
                                setBody(chunk)
                                headers {
                                    append(HttpHeaders.ContentRange, "$offset-$endRange")
                                }
                            }.also { res ->
                                if (res.status != HttpStatusCode.Accepted) {
                                    throw OCIException.UnexpectedStatus(
                                        HttpStatusCode.Accepted,
                                        res
                                    )
                                }

                                val status = res.headers.toUploadStatus()
                                uploading[expected] = status
                                offset = status.offset

                                send(offset + 1)
                            }

                            yield() // Allow cancellation between chunks
                        }
                    }

                    val final = checkNotNull(uploading[expected]?.location) {
                        "upload location unexpectedly null"
                    }

                    client.put(final) {
                        url {
                            encodedParameters.append("digest", expected.digest.toString())
                        }
                    }.also { res ->
                        if (res.status != HttpStatusCode.Created) {
                            throw OCIException.UnexpectedStatus(HttpStatusCode.Created, res)
                        }
                    }
                }
            }
        }.onCompletion { cause ->
            if (cause == null) uploading.remove(expected)
        }

    /**
     * [Pushing manifests](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests)
     */
    suspend fun tag(content: TaggableContent, ref: String): Result<Descriptor> = runCatching {
        val (ct, txt) = when (content) {
            is Manifest -> {
                val ct = when (val mt = content.mediaType) {
                    null -> {
                        ManifestMediaType
                    }

                    else -> {
                        ContentType.parse(mt)
                    }
                }
                val txt = Json.encodeToString(Manifest.serializer(), content)
                ct to txt
            }

            is Index -> {
                val ct = when (val mt = content.mediaType) {
                    null -> {
                        IndexMediaType
                    }

                    else -> {
                        ContentType.parse(mt)
                    }
                }
                val txt = Json.encodeToString(Index.serializer(), content)
                ct to txt
            }
        }

        val res = client.put(router.manifest(name, ref)) {
            contentType(ct)
            setBody(txt)
        }

        if (res.status != HttpStatusCode.Created) {
            throw OCIException.UnexpectedStatus(HttpStatusCode.Created, res)
        }

        // get digest from Location header
        val dg = Url(res.headers[HttpHeaders.Location]!!).pathSegments.last()

        Descriptor(ct.toString(), Digest(dg), txt.length.toLong())
    }
}
