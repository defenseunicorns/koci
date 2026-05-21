/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api

import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.internal.HttpWrapper
import com.defenseunicorns.koci.internal.KociLogger
import com.defenseunicorns.koci.internal.RepositoryPuller
import com.defenseunicorns.koci.internal.RepositoryPusher
import com.defenseunicorns.koci.internal.Router
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

/**
 * A handle to a single repository within a [Registry]. Obtained via [Registry.repo].
 *
 * Shares its parent registry's HTTP client and becomes unusable once the owning [Koci] is closed.
 */
public class Repository
internal constructor(
  public val name: String,
  push: PushConfig,
  pull: PullConfig,
  router: Router,
  httpWrapper: HttpWrapper,
  store: Layout,
  json: Json,
  logger: KociLogger,
) {

  private val puller: RepositoryPuller =
    RepositoryPuller(
      name = name,
      httpWrapper = httpWrapper,
      router = router,
      store = store,
      json = json,
      pull = pull,
      logger = logger,
    )

  private val pusher: RepositoryPusher =
    RepositoryPusher(
      name = name,
      caller = httpWrapper,
      router = router,
      store = store,
      json = json,
      pushConfig = push,
      puller = puller,
      logger = logger,
    )

  /** Returns `true` if [descriptor] exists on the remote, `false` on any failure. */
  public suspend fun exists(descriptor: Descriptor): Boolean = puller.exists(descriptor)

  /** Returns all tags in the repository, or an empty list on any failure. */
  public suspend fun tags(): List<String> = puller.tags()

  /**
   * Resolves [tag] to a [Descriptor]. When the tag points to an image index, supply
   * [platformResolver] to pick a single platform manifest; omit it to receive the index descriptor
   * itself. Returns `null` on failure or when no platform matches.
   */
  public suspend fun resolve(
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ): Descriptor? = puller.resolveManifest(tag, platformResolver)

  /**
   * Pulls a tagged image into the local layout, emitting [PullEvent.Progress] from 0 to 100 as
   * bytes land on disk. The terminal event is `Progress(100)` on success or [PullEvent.Failed].
   * Supply [platformResolver] to pull a single platform from an index.
   */
  public fun pull(tag: String, platformResolver: ((Platform) -> Boolean)? = null): Flow<PullEvent> =
    puller.pull(tag, platformResolver)

  /**
   * Pulls content addressed by [descriptor] into the local layout, emitting [PullEvent.Progress]
   * from 0 to 100. The terminal event is `Progress(100)` on success or [PullEvent.Failed].
   */
  public fun pull(descriptor: Descriptor): Flow<PullEvent> = puller.pull(descriptor)

  /**
   * Fetches [descriptor]'s bytes and passes the raw [InputStream] to [handler]. The stream is
   * closed when [handler] returns. Returns `null` on any transport or HTTP failure.
   */
  public suspend fun <T> fetch(descriptor: Descriptor, handler: (stream: InputStream) -> T): T? =
    puller.fetch(descriptor, handler)

  /**
   * Pushes a single blob. [expected] carries the digest and size used to address the upload session
   * and verify the bytes on the wire. Emits [PullEvent.Progress] from 0 to 100 as bytes are sent.
   * The terminal event is `Progress(100)` on success or [PullEvent.Failed].
   */
  public fun push(stream: InputStream, expected: Descriptor): Flow<PullEvent> =
    pusher.push(stream, expected)

  /**
   * Pushes [root] and every blob it references from the local layout. Blobs already on the remote
   * are skipped. Manifests and indexes land in post-order so the root only registers after all
   * children exist remotely. When [tag] is non-null the root is also tagged remotely and in the
   * local layout. Emits [PullEvent.Progress] from 0 to 100. The terminal event is `Progress(100)`
   * on success or [PullEvent.Failed].
   */
  public fun push(root: Descriptor, tag: String? = null): Flow<PullEvent> = pusher.push(root, tag)

  /**
   * Tags [content] under [ref] on the remote. [ref] must be a valid OCI tag. Returns the resulting
   * [Descriptor] on success, `null` otherwise.
   */
  public suspend fun tag(content: Manifest, ref: String): Descriptor? = pusher.tag(content, ref)

  /**
   * Tags [content] under [ref] on the remote. [ref] must be a valid OCI tag. Returns the resulting
   * [Descriptor] on success, `null` otherwise.
   */
  public suspend fun tag(content: Index, ref: String): Descriptor? = pusher.tag(content, ref)

  override fun toString(): String = "Repository(name=$name)"
}
