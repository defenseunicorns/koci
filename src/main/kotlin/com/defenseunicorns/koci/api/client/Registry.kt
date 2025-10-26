/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci.api.client

import com.defenseunicorns.koci.KociLogger
import com.defenseunicorns.koci.TransferCoordinator
import com.defenseunicorns.koci.api.KociLogLevel
import com.defenseunicorns.koci.api.errors.IOError
import com.defenseunicorns.koci.api.models.Descriptor
import com.defenseunicorns.koci.api.models.Platform
import com.defenseunicorns.koci.api.models.TagsResponse
import com.defenseunicorns.koci.auth.OCIAuthPlugin
import com.defenseunicorns.koci.http.Router
import com.defenseunicorns.koci.http.parseHTTPError
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.http.headers
import io.ktor.http.isSuccess
import java.io.InputStream
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Main entry point for interacting with OCI spec compliant registries.
 *
 * Provides access to registry-level operations such as:
 * - API version check (ping)
 * - Repository catalog listing
 * - Repository access via the [repo] extension function
 *
 * Configures HTTP client with appropriate headers and plugins for OCI registry communication.
 *
 * Example usage:
 * ```kotlin
 * val registry = Registry("https://ghcr.io")
 * val pingResult = registry.ping()
 * val repository = registry.repo("myorg/myrepo")
 * ```
 */
class Registry
internal constructor(
  registryUrl: String,
  private val logger: KociLogger,
  private val transferCoordinator: TransferCoordinator,
  private var client: HttpClient = HttpClient(CIO),
) {
  private val router = Router(registryUrl)
  val extensions = RegistryExtensions(client, router, logger)

  init {
    val timeoutPlugin = client.pluginOrNull(HttpTimeout)
    val ociAuthPlugin = client.pluginOrNull(OCIAuthPlugin)
    client =
      client.config {
        headers {
          // https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support
          append("Docker-Distribution-API-Version", "registry/2.0")
        }

        if (timeoutPlugin == null) {
          install(HttpTimeout) { this.requestTimeoutMillis = 10.minutes.inWholeMilliseconds }
        }
        if (ociAuthPlugin == null) {
          install(OCIAuthPlugin)
        }

        expectSuccess = false // Let responses through, handle errors explicitly
      }
  }

  /**
   * Checks API version compatibility with the registry.
   *
   * Performs a GET request to the /v2/ endpoint as specified in the OCI Distribution Specification.
   * A successful response indicates the registry implements the API and the client is authorized to
   * access it.
   *
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#determining-support">OCI
   *   Distribution Spec: API Version Check</a>
   */
  suspend fun ping(): Boolean {
    return try {
      val response = client.get(router.base())
      if (!response.status.isSuccess()) {
        return parseHTTPError(response)
      }
      true
    } catch (e: Exception) {
      logger.e("Network error: ${e.message}", e)
      false
    }
  }

  /**
   * Creates a Repository instance for interacting with a specific repository.
   *
   * Provides access to repository-specific operations such as tag listing, content pushing/pulling,
   * and manifest operations.
   *
   * @param name Repository name (e.g., "library/ubuntu")
   */
  fun repo(name: String) = Repository(client, router, name, logger, transferCoordinator)

  /**
   * Lists all tags in a repository.
   *
   * Performs a GET request to the /v2/{name}/tags/list endpoint as specified in the OCI spec.
   *
   * @param repository Repository name to list tags for
   * @see <a
   *   href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#listing-tags">OCI
   *   Distribution Spec: Listing Image Tags</a>
   */
  suspend fun tags(repository: String) = repo(repository).tags()

  /**
   * Resolves a tag to a content descriptor.
   *
   * Performs a HEAD or GET request to the /v2/{name}/manifests/{reference} endpoint as specified in
   * the OCI spec. For multi-platform images, the platformResolver can be used to select a specific
   * platform.
   *
   * @param repository Repository name
   * @param tag Tag to resolve
   * @param platformResolver Optional function to select platform from index manifest
   * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md#pull">OCI
   *   Distribution Spec: Existing Manifests</a>
   */
  suspend fun resolve(
    repository: Repository,
    tag: String,
    platformResolver: ((Platform) -> Boolean)? = null,
  ) = repository.resolve(tag, platformResolver)

  /**
   * Pushes a blob to the repository.
   *
   * Uploads blob content with support for resumable uploads. Verifies content integrity through
   * digest validation.
   *
   * @param repository Repository name
   * @param stream Input stream containing blob data
   * @param expected Descriptor with expected size and digest
   * @return Flow emitting progress updates as bytes uploaded
   */
  fun push(repository: Repository, stream: InputStream, expected: Descriptor) =
    repository.push(stream, expected)

  /**
   * Pulls content by tag and stores it in the provided layout.
   *
   * Resolves the tag to a descriptor, then pulls the manifest and all referenced content. For
   * multi-platform images, the platformResolver can be used to select a specific platform.
   *
   * @param repository Repository name
   * @param tag Tag to pull
   * @param storage Layout to store content in
   * @param platformResolver Optional function to select platform from index manifest
   */
  fun pull(
    repository: Repository,
    tag: String,
    storage: Layout,
    platformResolver: ((Platform) -> Boolean)? = null,
  ) = repository.pull(tag, storage, platformResolver)

  /**
   * Lists all repositories with their tags.
   *
   * This is a convenience method that combines the catalog and tags endpoints to provide a
   * flattened list of all repositories and their tags. This is not part of the OCI spec but
   * simplifies common workflows.
   *
   * Emits OCIResult<TagsResponse> for each repository. Errors are emitted as OCIResult.Err.
   *
   * @param n Number of repositories to return per page in the catalog request
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun list(n: Int = 1000): Flow<TagsResponse?> =
    extensions
      .catalog(n)
      .filterNotNull()
      .flatMapConcat { catalogResponse -> catalogResponse.repositories.asFlow() }
      .map { repo -> tags(repo) }

  companion object {
    fun create(registryUrl: String, logLevel: KociLogLevel = KociLogLevel.DEBUG): Registry {

      val logger = KociLogger(logLevel)

      return Registry(
        registryUrl = registryUrl,
        transferCoordinator = TransferCoordinator(logger),
        logger = logger,
      )
    }
  }
}
