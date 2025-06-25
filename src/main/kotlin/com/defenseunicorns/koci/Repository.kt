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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt


private val activeDownloads = ConcurrentHashMap<Descriptor, Pair<Mutex, AtomicInteger>>()

/**
 * Extracts upload status from HTTP response headers for resumable uploads.
 *
 * Parses Location and Range headers to determine upload state and handles the optional
 * OCI-Chunk-Min-Length header.
 *
 * @return [UploadStatus] with location URL, byte offset, and minimum chunk size
 * @throws IllegalStateException if required headers are missing or malformed
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#resuming-an-upload">OCI Distribution Spec: Resuming an Upload</a>
 */
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

/**
 * OCI spec compliant repository client.
 *
 * Supports all required operations including pulling/pushing blobs and manifests,
 * content verification, resumable uploads, cross-repository mounting, and tag management.
 *
 * @property client HTTP client for registry communication
 * @property router URL routing for registry endpoints
 * @property name Repository name in format "[host]/[namespace]/[repository]"
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md">OCI spec</a>
 */
@Suppress("detekt:TooManyFunctions")
class Repository(
    private val client: HttpClient,
    private val router: Router,
    private val name: String,
) {
    /**
     * Tracks in-progress blob uploads for resumable operations.
     */
    private val uploading = ConcurrentHashMap<Descriptor, UploadStatus>()

    /**
     * Checks if a blob or manifest exists in the repository.
     *
     * Uses HEAD request to verify content existence without transferring data.
     * Routes to appropriate endpoint based on media type (manifest or blob).
     *
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#checking-if-content-exists-in-the-registry">OCI Distribution Spec: Checking if Content Exists</a>
     */
    suspend fun exists(descriptor: Descriptor): Result<Boolean> = runCatching {
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
     * Resolves a tag to a descriptor, with optional platform filtering for index manifests.
     *
     * First performs a HEAD request to determine content type, then handles accordingly:
     * - For regular manifests: Returns descriptor directly
     * - For index manifests: Returns full index or uses platformResolver to select specific platform
     *
     * @param tag Tag to resolve
     * @param platformResolver Optional function to select specific platform from index manifest
     * @throws OCIException.PlatformNotFound if platformResolver provided but no matching platform found
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI Distribution Spec: Pulling Manifests</a>
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
     * Removes a blob or manifest from the repository.
     *
     * Routes to appropriate endpoint based on media type. Note that per OCI spec,
     * registries MAY implement deletion or MAY disable it entirely.
     *
     * @param descriptor Content descriptor to remove
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-blobs">OCI Distribution Spec: Deleting Blobs</a>
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#deleting-manifests">OCI Distribution Spec: Deleting Manifests</a>
     *
     * TODO: Similarly, a registry MAY implement tag deletion, while others MAY allow deletion only by manifest.
     */
    suspend fun remove(descriptor: Descriptor): Result<Boolean> = runCatching {
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

    /**
     * Generic content fetcher with custom processing.
     *
     * Retrieves content and processes it with the provided handler function.
     * Used internally by [manifest], [index], and [pull] methods.
     *
     * @param descriptor Content descriptor with mediaType and digest
     * @param handler Function to process the input stream
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI Distribution Spec: Pulling Blobs</a>
     */
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

    /**
     * Fetches and deserializes a manifest from the registry.
     *
     * Retrieves a manifest and deserializes it into a Manifest object for
     * programmatic access to layers, config, and annotations.
     *
     * @param descriptor Manifest descriptor with digest and mediaType
     * @throws IllegalArgumentException if descriptor mediaType is not a manifest
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI Distribution Spec: Pulling Manifests</a>
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun manifest(descriptor: Descriptor): Result<Manifest> = runCatching {
        require(descriptor.mediaType == MANIFEST_MEDIA_TYPE)
        fetch(descriptor, Json::decodeFromStream)
    }

    /**
     * Fetches and deserializes an index manifest (multi-platform manifest).
     *
     * Retrieves an index manifest and deserializes it into an Index object for
     * accessing platform-specific manifests in multi-platform images.
     *
     * @param descriptor Index descriptor with digest and mediaType
     * @throws IllegalArgumentException if descriptor mediaType is not an index
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI Distribution Spec: Pulling Manifests</a>
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun index(descriptor: Descriptor): Result<Index> = runCatching {
        require(descriptor.mediaType == INDEX_MEDIA_TYPE)
        fetch(descriptor, Json::decodeFromStream)
    }

    /**
     * Lists all tags in the repository.
     * Retrieves available tags for content discovery.
     *
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI Distribution Spec: Listing Tags</a>
     *
     * TODO: Implement pagination support as described in the specification
     */
    suspend fun tags(): Result<TagsResponse> = runCatching {
        val res = client.get(router.tags(name)) {
            attributes.appendScopes(scopeRepository(name, ACTION_PULL))
        }
        Json.decodeFromString(res.body())
    }

    /**
     * Pulls an image by tag and stores it in the provided layout.
     *
     * Resolves tag to descriptor, then pulls manifest and all referenced blobs.
     * For multi-platform images, uses platformResolver to select specific platform.
     *
     * @param tag Tag to pull
     * @param store Layout to store content in
     * @param platformResolver Optional function to select platform from index
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-manifests">OCI Distribution Spec: Pulling Manifests</a>
     */
    fun pull(tag: String, store: Layout, platformResolver: ((Platform) -> Boolean)? = null): Flow<Int> = channelFlow {
        resolve(tag, platformResolver).map { desc ->
            pull(desc, store).onCompletion { cause ->
                if (cause == null) {
                    val ref = Reference(
                        registry = router.base(), repository = name, reference = tag
                    )
                    val ok = store.exists(desc).getOrThrow()
                    if (!ok) {
                        throw OCIException.IncompletePull(ref)
                    }
                    // if pull was successful, tag the resolved desc w/ the image's ref
                    store.tag(desc, ref).getOrThrow()
                }
            }.collect { progress ->
                send(progress)
            }
        }.getOrThrow()
    }

    /**
     * Pulls content by descriptor and stores it in the provided layout.
     *
     * Handles different content types appropriately (manifests, indices, blobs).
     * For manifests and indices, pulls all referenced content recursively.
     *
     * @param descriptor Content descriptor to pull
     * @param store Layout to store content in
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

                val acc = AtomicInteger(0)

                println("about to pull $descriptor")

                layersToFetch.asFlow().map { layer ->
                    flow {
                        println("+ ${layer.digest.hex.slice(0..8)} ${store.pushing}")
                        copy(layer, store).collect { progress ->
                            val curr = acc.addAndGet(progress)
                            emit((curr.toDouble() * 100 / total).roundToInt())
                        }
                        println("- ${layer.digest.hex.slice(0..8)} ${store.pushing}")
                    }
                }.flattenMerge(concurrency = 3) // TODO: figure out best API to expose concurrency settings
                    .onCompletion { cause ->
                        if (cause == null) {
                            copy(descriptor, store).collect { progress ->
                                val curr = acc.addAndGet(progress)
                                emit((curr.toDouble() * 100 / total).roundToInt())
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

    /**
     * Copies a blob to a local layout with progress reporting.
     *
     * Downloads blob and stores it in layout, with support for resumable downloads
     * when registry supports range requests.
     *
     * @param descriptor Blob descriptor to copy
     * @param store Layout to store blob in
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pulling-blobs">OCI Distribution Spec: Pulling Blobs</a>
     *
     * Note: For complete images or manifests, use [pull] methods instead.
     */
    private fun copy(descriptor: Descriptor, store: Layout): Flow<Int> = channelFlow {
        // Get or create a mutex for this descriptor to prevent concurrent downloads
        val (mutex, refCount) = activeDownloads.computeIfAbsent(descriptor) {
            Pair(Mutex(), AtomicInteger(0))
        }

        // Increment reference count to track active users of this mutex
        refCount.incrementAndGet()

        try {
            // Acquire the lock before checking if the descriptor exists or downloading
            mutex.withLock {
                val ok = store.exists(descriptor)

                // if the descriptor is 100% downloaded w/ size and sha matching, early return
                if (ok.getOrDefault(false)) {
                    send(descriptor.size.toInt())
                    return@withLock
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
        } finally {
            // Get the current pair
            val pair = activeDownloads[descriptor]
            if (pair != null) {
                val count = pair.second.decrementAndGet()
                // Only remove from map if this was the last reference
                if (count <= 0) {
                    activeDownloads.remove(descriptor, pair)
                }
            }
        }
    }

    /**
     * Starts or resumes a blob upload session.
     *
     * Initiates new upload or resumes existing one if previously interrupted.
     * Implements the initial phase of the blob upload process.
     *
     * @param descriptor Blob descriptor to upload
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#starting-an-upload">OCI Distribution Spec: Starting an Upload</a>
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
     * Pushes a blob to the repository with chunked and resumable uploads.
     *
     * Uses monolithic upload for small blobs and chunked uploads for large ones (>5MB).
     * Supports resuming interrupted uploads from the last successful byte offset.
     * Verifies content integrity through digest validation.
     *
     * @param stream Input stream containing blob data
     * @param expected Descriptor with expected size and digest
     * @throws OCIException.DigestMismatch if computed digest doesn't match expected
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-blobs">OCI Distribution Spec: Pushing Blobs</a>
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
     * Tags a manifest or index in the repository.
     *
     * Pushes content to registry and associates it with a tag. Handles content type
     * negotiation based on whether content is a manifest or index. Tags must match
     * regex: [TagRegex].
     *
     * @param content Manifest or index content to tag
     * @param tag Tag to associate with the content
     * @throws IllegalArgumentException if tag format is invalid
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pushing-manifests">OCI Distribution Spec: Pushing Manifests</a>
     */
    suspend fun tag(content: Versioned, ref: String): Result<Descriptor> = runCatching {
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

    /**
     * Mounts a blob from another repository.
     *
     * Reuses blobs that already exist in registry without downloading and re-uploading.
     * Handles both successful mounts and fallback to creating an upload session when
     * mounting is not supported or fails.
     *
     * @param descriptor Blob descriptor to mount (must not be a manifest or index)
     * @param sourceRepository Source repository from which to mount the blob
     * @throws IllegalArgumentException if descriptor is a manifest or index
     * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#mounting-a-blob-from-another-repository">OCI Distribution Spec: Mounting a Blob</a>
     */
    suspend fun mount(
        descriptor: Descriptor,
        sourceRepository: String
    ): Result<Boolean> = runCatching {
        require(descriptor.mediaType != MANIFEST_MEDIA_TYPE)
        require(descriptor.mediaType != INDEX_MEDIA_TYPE)

        // If the blob is already being uploaded, don't try to mount it
        if (uploading.containsKey(descriptor)) {
            return@runCatching false
        }

        if (exists(descriptor).getOrDefault(false)) {
            return@runCatching true
        }

        val mountUrl = router.blobMount(name, sourceRepository, descriptor)
        val res = client.post(mountUrl) {
            headers[HttpHeaders.ContentLength] = "0"
            attributes.appendScopes(
                scopeRepository(name, ACTION_PULL, ACTION_PUSH),
                scopeRepository(sourceRepository, ACTION_PULL)
            )
        }

        when (res.status) {
            HttpStatusCode.Created -> {
                val locationHeader = res.headers[HttpHeaders.Location]
                requireNotNull(locationHeader) {
                    "Registry did not provide a Location header in the mount response"
                }
                true
            }

            HttpStatusCode.Accepted -> {
                val uploadStatus = res.headers.toUploadStatus()
                uploading[descriptor] = uploadStatus
                false
            }

            else -> throw OCIException.UnexpectedStatus(
                HttpStatusCode.Created, res
            )
        }
    }
}
