/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.internal

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Index
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.OciConstants.INDEX_MEDIA_TYPE
import com.defenseunicorns.koci.api.OciConstants.MANIFEST_MEDIA_TYPE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import okio.Buffer

/**
 * Flattened view of an OCI artifact tree. [containers] are manifests and indexes in post-order
 * (deepest first, root last) paired with their on-the-wire bytes; [blobs] holds every referenced
 * layer and config descriptor; [totalBytes] is the global denominator for byte-weighted progress.
 *
 * Produced by [walkTree], consumed by both [RepositoryPuller] (writing into the layout) and
 * [RepositoryPusher] (uploading to the registry).
 */
internal data class TreeWalk(
  val containers: List<Pair<Descriptor, Buffer>>,
  val blobs: List<Descriptor>,
  val totalBytes: Long,
)

/**
 * Walks the descriptor tree rooted at [root], fetching each manifest or index via [fetchContainer]
 * and accumulating the leaves as blobs. Returns `null` on any fetch or parse failure. The caller
 * supplies the byte source: the puller fetches from the registry, the pusher reads from the local
 * layout. The walk shape is identical either way.
 */
internal suspend fun walkTree(
  root: Descriptor,
  json: Json,
  logger: KociLogger,
  fetchContainer: suspend (Descriptor) -> Buffer?,
): TreeWalk? {
  val containers = mutableListOf<Pair<Descriptor, Buffer>>()
  val blobs = mutableListOf<Descriptor>()
  val queue = ArrayDeque<Descriptor>().apply { add(root) }

  while (queue.isNotEmpty()) {
    val d = queue.removeFirst()
    when (d.mediaType) {
      MANIFEST_MEDIA_TYPE,
      INDEX_MEDIA_TYPE -> {
        val buffer = fetchContainer(d) ?: return null
        val children = parseChildren(d, buffer, json, logger) ?: return null
        containers += d to buffer
        queue.addAll(children)
      }
      else -> blobs += d
    }
  }

  // Post-order (deepest first, root last) is the correct write order for both push and pull.
  val ordered = containers.asReversed()
  return TreeWalk(
    containers = ordered,
    blobs = blobs,
    totalBytes = ordered.sumOf { (d, _) -> d.size } + blobs.sumOf { it.size },
  )
}

/**
 * Runs [processBlob] over every blob concurrently, bounded by [concurrency]. Returns `true` only if
 * every blob succeeds. Completion order is not guaranteed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TreeWalk.dispatchBlobs(
  concurrency: Int,
  processBlob: suspend (Descriptor) -> Boolean,
): Boolean =
  blobs
    .asFlow()
    .flatMapMerge(concurrency = concurrency) { blob -> flow { emit(processBlob(blob)) } }
    .toList()
    .all { it }

/**
 * Parses [descriptor]'s body into its referenced child descriptors. Reads via [Buffer.peek] so the
 * source buffer survives intact for the next consumer. Returns `null` on parse failure.
 */
private fun parseChildren(
  descriptor: Descriptor,
  buffer: Buffer,
  json: Json,
  logger: KociLogger,
): List<Descriptor>? =
  try {
    when (descriptor.mediaType) {
      MANIFEST_MEDIA_TYPE -> {
        val m = json.decodeFromString<Manifest>(buffer.peek().readUtf8())
        m.layers + m.config
      }
      INDEX_MEDIA_TYPE -> json.decodeFromString<Index>(buffer.peek().readUtf8()).manifests
      else -> emptyList()
    }
  } catch (e: Exception) {
    logger.error(e) { "failed to parse ${descriptor.mediaType} for ${descriptor.digest}" }
    null
  }
