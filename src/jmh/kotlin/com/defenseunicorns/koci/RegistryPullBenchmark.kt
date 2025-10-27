/*
 * Copyright 2024-2025 Defense Unicorns
 * SPDX-License-Identifier: Apache-2.0
 */

package com.defenseunicorns.koci

import com.defenseunicorns.koci.api.client.Layout
import com.defenseunicorns.koci.api.client.Registry
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup

/**
 * Real-world benchmark pulling from an actual registry.
 *
 * This is an integration benchmark that tests the full pull pipeline:
 * - Network I/O from registry
 * - Transfer coordination
 * - Staging directory writes
 * - Verification (size + digest)
 * - Atomic move to blobs
 * - Index updates
 *
 * NOTE: This requires a running registry at the configured URL. Results will vary based on network
 * conditions and registry performance.
 *
 * Run with: ./gradlew jmh --includes=RegistryPullBenchmark
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 1, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
open class RegistryPullBenchmark {

  private lateinit var registry: Registry
  private lateinit var layout: Layout
  private lateinit var tempDir: String

  // Configure your registry URL here
  private val registryUrl = System.getenv("BENCHMARK_REGISTRY_URL") ?: "https://192.168.3.240:5000"
  private val testRepo = System.getenv("BENCHMARK_REPO") ?: "core-base"
  private val testTag = System.getenv("BENCHMARK_TAG") ?: "0.35.0-upstream"

  @Setup(Level.Trial)
  fun setup() {
    tempDir = Files.createTempDirectory("koci-bench-registry").toString()

    layout = Layout.create(tempDir, strictChecking = true).getOrNull()!!

    registry = Registry.create(registryUrl = registryUrl, logLevel = KociLogLevel.ASSERT)

    println("🔗 Benchmarking against: $registryUrl/$testRepo:$testTag")
  }

  @TearDown(Level.Trial)
  fun tearDown() {
    // Clean up temporary directory
    Files.walk(java.nio.file.Paths.get(tempDir)).sorted(Comparator.reverseOrder()).forEach {
      Files.deleteIfExists(it)
    }
  }

  @TearDown(Level.Invocation)
  fun cleanupAfterInvocation() {
    // Remove pulled content after each iteration to ensure fresh pulls
    runBlocking {
      val descriptor = registry.resolve(testRepo, testTag).getOrNull()
      descriptor?.let { layout.remove(it) }
    }
  }

  /** Benchmark: Single pull from registry. Measures end-to-end time for a complete image pull. */
  @Benchmark fun singlePull() = runBlocking { registry.pull(testRepo, testTag, layout).collect() }

  /**
   * Benchmark: 3 concurrent pulls of the same image. Tests coordinator efficiency - only one should
   * download, others wait.
   *
   * This simulates multiple pods/containers trying to pull the same image.
   */
  @Benchmark
  fun concurrentPullsSameImage() = runBlocking {
    val jobs = (1..3).map { async { registry.pull(testRepo, testTag, layout).collect() } }
    jobs.awaitAll()
  }

  /**
   * Benchmark: Pull with existing content (cache hit). Tests how fast the system detects and skips
   * already-downloaded content.
   */
  @Benchmark
  fun pullWithCache() = runBlocking {
    // First pull to populate cache
    registry.pull(testRepo, testTag, layout).collect()

    // Second pull should be much faster (everything exists)
    registry.pull(testRepo, testTag, layout).collect()
  }

  /**
   * Benchmark: List repositories and pull first one. Tests the full workflow from discovery to
   * download.
   */
  @Benchmark
  fun listAndPull() = runBlocking {
    registry.list().collect { result ->
      result.getOrNull()?.let { tagsResponse ->
        if (tagsResponse.name == testRepo && tagsResponse.tags.isNotEmpty()) {
          registry.pull(tagsResponse.name, tagsResponse.tags.first(), layout).collect()
        }
      }
    }
  }
}
