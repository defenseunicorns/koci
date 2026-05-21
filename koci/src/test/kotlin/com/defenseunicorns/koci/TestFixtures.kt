/*
 * Copyright 2026 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Digest
import com.defenseunicorns.koci.api.Layout
import com.defenseunicorns.koci.api.OciConstants
import com.defenseunicorns.koci.api.RegisteredAlgorithm
import com.defenseunicorns.koci.api.Registry
import com.defenseunicorns.koci.api.config.PullConfig
import com.defenseunicorns.koci.api.config.PushConfig
import com.defenseunicorns.koci.internal.HttpWrapper
import com.defenseunicorns.koci.internal.KociLogger
import com.defenseunicorns.koci.internal.Router
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

object TestFixtures {

  internal val testJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  internal fun digestOf(bytes: ByteArray): Digest {
    val raw = RegisteredAlgorithm.SHA256.hasher().digest(bytes)
    return Digest(RegisteredAlgorithm.SHA256, raw.joinToString("") { "%02x".format(it) })
  }

  internal fun buildLayout(fs: FakeFileSystem = FakeFileSystem()): Layout =
    Layout(
        root = "/oci".toPath(),
        fileSystem = fs,
        dispatcher = Dispatchers.IO,
        json = testJson,
        logger = NoOpLogger,
      )
      .also { it.create() }

  internal suspend fun Layout.writeBlob(
    bytes: ByteArray,
    mediaType: String = "application/octet-stream",
  ): Descriptor {
    val descriptor =
      Descriptor(mediaType = mediaType, digest = digestOf(bytes), size = bytes.size.toLong())
    push(descriptor, Buffer().apply { write(bytes) })
    return descriptor
  }

  internal fun fakeRegistry(
    handler: MockRequestHandler,
    url: String = "https://registry.example.com",
    store: Layout = buildLayout(),
  ): Registry {
    val client =
      HttpClient(MockEngine) {
        engine { addHandler(handler) }
        install(ContentNegotiation) {
          json(testJson)
          json(testJson, contentType = ContentType.parse(OciConstants.INDEX_MEDIA_TYPE))
          json(testJson, contentType = ContentType.parse(OciConstants.MANIFEST_MEDIA_TYPE))
        }
      }
    return Registry(
      url = url,
      push = PushConfig(),
      pull = PullConfig(),
      httpWrapper = HttpWrapper(client, NoOpLogger),
      router = Router(url),
      store = store,
      json = testJson,
      logger = NoOpLogger,
    )
  }

  internal fun fakeRepo(
    name: String = "myrepo",
    handler: MockRequestHandler,
    store: Layout = buildLayout(),
  ) = fakeRegistry(handler = handler, store = store).repo(name)

  internal object NoOpLogger : KociLogger {
    override fun debug(message: () -> String) = Unit

    override fun info(message: () -> String) = Unit

    override fun warn(message: () -> String) = Unit

    override fun error(throwable: Throwable?, message: () -> String) = Unit
  }
}
