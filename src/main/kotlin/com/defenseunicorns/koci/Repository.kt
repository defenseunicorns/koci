/*
 * Copyright 2024 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

fun Headers.toUploadStatus(): UploadStatus {
    val location = checkNotNull(this[HttpHeaders.Location]) {
        "missing Location header"
    }
    val range = checkNotNull(this[HttpHeaders.Range]) {
        "missing Range header"
    }
    val re = Regex("^([0-9]+)-([0-9]+)\$")
    val offset = checkNotNull(re.matchEntire(range)?.groupValues?.last()) {
        "invalid Range header"
    }

    // this header MAY not exist
    val minChunk = this["OCI-Chunk-Min-Length"]?.toLong() ?: 0L

    return UploadStatus(location, offset.toLong(), minChunk)
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
            MANIFEST_MEDIA_TYPE,
            INDEX_MEDIA_TYPE,
                -> router.manifest(name, descriptor)

            else -> router.blob(name, descriptor)
        }
        client.head(endpoint) {
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        }.status.isSuccess()
    }

    /**
     * [HEAD|GET /v2/<name>/manifests/<reference>](https://distribution.github.io/distribution/spec/api/#existing-manifests)
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun resolve(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Result<Descriptor> =
        runCatching {
            val endpoint = router.manifest(name, tag)
            val response = client.head(endpoint) {
                accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
                accept(ContentType.parse(INDEX_MEDIA_TYPE))
                attributes.appendScopes(scopeRepository(name, ACTION_PULL))
            }

            when (response.contentType()?.toString()) {
                INDEX_MEDIA_TYPE -> {
                    client.prepareGet(endpoint) {
                        accept(ContentType.parse(INDEX_MEDIA_TYPE))
                        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
                    }.execute { res ->
                        when (platformResolver) {
                            null -> {
                                Descriptor.fromInputStream(
                                    mediaType = INDEX_MEDIA_TYPE,
                                    stream = res.body() as InputStream
                                )
                            }

                            else -> {
                                val index = Json.decodeFromStream<Index>(res.body())

                                try {
                                    index.manifests.first { desc ->
                                        desc.platform != null && platformResolver(desc.platform)
                                    }
                                } catch (_: NoSuchElementException) {
                                    throw OCIException.PlatformNotFound(index)
                                }
                            }
                        }
                    }
                }

                MANIFEST_MEDIA_TYPE -> {
                    client.prepareGet(endpoint) {
                        accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
                        attributes.appendScopes(scopeRepository(name, ACTION_PULL))
                    }.execute { res ->
                        Descriptor.fromInputStream(
                            mediaType = MANIFEST_MEDIA_TYPE, stream = res.body() as InputStream
                        )
                    }
                }

                else -> throw OCIException.ManifestNotSupported(
                    endpoint, response.contentType()
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
            MANIFEST_MEDIA_TYPE,
            INDEX_MEDIA_TYPE,
                -> router.manifest(name, descriptor)

            else -> router.blob(name, descriptor)
        }

        client.delete(endpoint) {
            attributes.appendScopes(scopeRepository(name, ACTION_DELETE))
        }.status.isSuccess()
    }

    suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T {
        return client.prepareGet(
            when (descriptor.mediaType) {
                MANIFEST_MEDIA_TYPE, INDEX_MEDIA_TYPE -> router.manifest(name, descriptor)
                else -> router.blob(name, descriptor)
            }
        ) {
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))

            when (descriptor.mediaType) {
                MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
                INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
            }
        }.execute { res ->
            res.body<InputStream>().use { stream ->
                handler(stream)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun manifest(descriptor: Descriptor): Result<Manifest> = runCatching {
        require(descriptor.mediaType == MANIFEST_MEDIA_TYPE)
        fetch(descriptor) { stream ->
            Json.decodeFromStream(stream)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun index(descriptor: Descriptor): Result<Index> = runCatching {
        require(descriptor.mediaType == INDEX_MEDIA_TYPE)
        fetch(descriptor) { stream ->
            Json.decodeFromStream(stream)
        }
    }

    /**
     * [GET /v2/<name>/tags/list](https://distribution.github.io/distribution/spec/api/#listing-image-tags)
     */
    suspend fun tags(): Result<TagsResponse> = runCatching {
        val res = client.get(router.tags(name)) {
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        }
        Json.decodeFromString(res.body())
    }

    /**
     * Pull and tag.
     */
    fun pull(tag: String, store: Layout, platformResolver: ((Platform) -> Boolean)? = null): Flow<Int> = channelFlow {
        resolve(tag, platformResolver).map {
            pull(it, store).onCompletion { cause ->
                if (cause == null) {
                    val ref = Reference(
                        registry = router.base(), repository = name, reference = tag
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
     * Does NOT tag.
     *
     * [GET /v2/<name>/blobs/<digest>](https://distribution.github.io/distribution/spec/api/#pulling-a-layer)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pull(descriptor: Descriptor, store: Layout): Flow<Int> = channelFlow {
        when (descriptor.mediaType) {
            INDEX_MEDIA_TYPE -> {
                if (store.exists(descriptor).getOrDefault(false)) {
                    send(100)
                    return@channelFlow
                }
                val index = index(descriptor).getOrThrow()
                val total = index.manifests.size
                var completedPulls = 0
                index.manifests.forEach { manifestDescriptor ->
                    pull(manifestDescriptor, store).collect { manifestProgress ->
                        val currentManifestContribution = manifestProgress.toDouble() / 100.0
                        val overallProgress =
                            ((completedPulls + currentManifestContribution) / total * 100).roundToInt()
                        send(overallProgress)
                    }
                    completedPulls += 1
                }
                copy(descriptor, store).collect()
                send(100)
            }

            MANIFEST_MEDIA_TYPE -> {
                if (store.exists(descriptor).getOrDefault(false)) {
                    send(100)
                    return@channelFlow
                }
                val manifest = manifest(descriptor).getOrThrow()
                val layersToFetch = manifest.layers.toMutableList() + manifest.config
                val total = layersToFetch.sumOf { it.size } + manifest.config.size + descriptor.size

                var acc = 0.0

                layersToFetch.asFlow().map { layer ->
                    flow {
                        copy(layer, store).collect { progress ->
                            acc += progress
                            emit((acc * 100 / total).roundToInt())
                        }
                    }
                }.flattenMerge(concurrency = 3) // TODO: figure out best API to expose concurrency settings
                    .onCompletion { cause ->
                        if (cause == null) {
                            copy(descriptor, store).collect { progress ->
                                acc += progress
                                emit((acc * 100 / total).roundToInt())
                            }
                        }
                    }.collect { progress ->
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

        require(descriptor.mediaType != MANIFEST_MEDIA_TYPE)
        require(descriptor.mediaType != INDEX_MEDIA_TYPE)

        val response = runCatching {
            client.head(router.blob(name, descriptor)) {
                attributes.appendScopes(scopeRepository(name, ACTION_PULL))
            }
        }.getOrNull()
        val rangeSupported = response?.headers?.get("Accept-Ranges") == "bytes"

        return synchronized(this) {
            supportsRange ?: run {
                supportsRange = rangeSupported
                rangeSupported
            }
        }
    }

    private fun copy(descriptor: Descriptor, store: Layout): Flow<Int> = channelFlow {
        val ok = store.exists(descriptor)

        // if the descriptor is 100% downloaded w/ size and sha matching, early return
        if (ok.getOrDefault(false)) {
            send(descriptor.size.toInt())
            return@channelFlow
        }

        val endpoint = when (descriptor.mediaType) {
            MANIFEST_MEDIA_TYPE,
            INDEX_MEDIA_TYPE,
                -> router.manifest(name, descriptor)

            else -> router.blob(name, descriptor)
        }

        client.prepareGet(endpoint) {
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))

            when (descriptor.mediaType) {
                MANIFEST_MEDIA_TYPE -> accept(ContentType.parse(MANIFEST_MEDIA_TYPE))
                INDEX_MEDIA_TYPE -> accept(ContentType.parse(INDEX_MEDIA_TYPE))
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

    /**
     * TODO: this function could use some love
     */
    @Suppress("detekt:NestedBlockDepth", "detekt:ReturnCount", "detekt:ThrowsCount")
    private suspend fun startOrResumeUpload(descriptor: Descriptor): UploadStatus {
        return when (val prev = uploading[descriptor]) {
            null -> {
                val res = client.post(router.uploads(name)) {
                    headers[HttpHeaders.ContentLength] = 0.toString()
                    attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                }
                if (res.status != HttpStatusCode.Accepted) {
                    throw OCIException.UnexpectedStatus(HttpStatusCode.Accepted, res)
                }
                return res.headers.toUploadStatus()
            }

            else -> {
                if (prev.offset > 0) {
                    try {
                        client.get(router.parseUploadLocation(prev.location)) {
                            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
                        }.also { res ->
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
     * If the content being pushed is > 5MB the push will be
     * [chunked](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-a-blob-in-chunks),
     * otherwise performed in a
     * [single POST](https://github.com/opencontainers/distribution-spec/blob/main/spec.md#single-post)
     *
     * If errors occur, calling this function again will attempt to resume upload at whatever byte offset
     * the previous attempt stopped at
     */
    @Suppress("detekt:LongMethod", "detekt:CyclomaticComplexMethod")
    fun push(stream: InputStream, expected: Descriptor): Flow<Long> = channelFlow {
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
                    attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
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
                    if (offset > 0) withContext(Dispatchers.IO) { s.skipNBytes(offset + 1) }

                    while (isActive) {
                        val chunk = withContext(Dispatchers.IO) { s.readNBytes(start.minChunkSize.toInt()) }

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
                            attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
                        }.also { res ->
                            if (res.status != HttpStatusCode.Accepted) {
                                throw OCIException.UnexpectedStatus(
                                    HttpStatusCode.Accepted, res
                                )
                            }

                            val status = res.headers.toUploadStatus()
                            uploading[expected] = status
                            offset = status.offset

                            send(offset + 1)

                            yield()
                        }
                    }
                }

                val final = checkNotNull(uploading[expected]?.location) {
                    "upload location unexpectedly null"
                }

                client.put(final) {
                    url {
                        encodedParameters.append("digest", expected.digest.toString())
                    }
                    attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
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
        requireNotNull(TagRegex.matchEntire(ref)) { "$ref does not satisfy $TagRegex" }
        val (ct, txt) = when (content) {
            is Manifest -> {
                val ct = when (val mt = content.mediaType) {
                    null -> MANIFEST_MEDIA_TYPE
                    else -> mt
                }
                val txt = Json.encodeToString(Manifest.serializer(), content)
                ct to txt
            }

            is Index -> {
                val ct = when (val mt = content.mediaType) {
                    null -> INDEX_MEDIA_TYPE
                    else -> mt
                }
                val txt = Json.encodeToString(Index.serializer(), content)
                ct to txt
            }
        }

        val res = client.put(router.manifest(name, ref)) {
            contentType(ContentType.parse(ct))
            setBody(txt)
            attributes.appendScopes(scopeRepository(name, ACTION_PULL, ACTION_PUSH))
        }

        if (res.status != HttpStatusCode.Created) {
            throw OCIException.UnexpectedStatus(HttpStatusCode.Created, res)
        }

        // get digest from Location header
        val dg = Url(res.headers[HttpHeaders.Location]!!).segments.last()

        Descriptor(ct, Digest(dg), txt.length.toLong())
    }
}
