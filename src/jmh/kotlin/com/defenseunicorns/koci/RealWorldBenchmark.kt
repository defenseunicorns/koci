/// *
// * Copyright 2024-2025 Defense Unicorns
// * SPDX-License-Identifier: Apache-2.0
// */
//
// package com.defenseunicorns.koci
//
// import com.defenseunicorns.koci.client.Layout
// import com.defenseunicorns.koci.client.Registry
// import java.nio.file.Files
// import java.util.concurrent.TimeUnit
// import kotlinx.coroutines.flow.collect
// import kotlinx.coroutines.launch
// import kotlinx.coroutines.runBlocking
// import org.openjdk.jmh.annotations.Benchmark
// import org.openjdk.jmh.annotations.BenchmarkMode
// import org.openjdk.jmh.annotations.Fork
// import org.openjdk.jmh.annotations.Level
// import org.openjdk.jmh.annotations.Measurement
// import org.openjdk.jmh.annotations.Mode
// import org.openjdk.jmh.annotations.OutputTimeUnit
// import org.openjdk.jmh.annotations.Scope
// import org.openjdk.jmh.annotations.Setup
// import org.openjdk.jmh.annotations.State
// import org.openjdk.jmh.annotations.TearDown
// import org.openjdk.jmh.annotations.Warmup
//
/// **
// * Real-world benchmark that mimics Main.kt behavior.
// *
// * Pulls all images from a registry catalog, similar to:
// * ```kotlin
// * registry.list().collect {
// *   it.getOrNull()?.let {
// *     launch { registry.pull(it.name, it.tags.first(), layout).collect() }
// *   }
// * }
// * ```
// *
// * This tests the full system under realistic workload:
// * - Multiple concurrent pulls
// * - Transfer coordination across images
// * - Staging + verification + move pipeline
// * - Index management
// *
// * Run with: ./gradlew jmh --includes=RealWorldBenchmark
// */
// @State(Scope.Benchmark)
// @BenchmarkMode(Mode.SingleShotTime)
// @OutputTimeUnit(TimeUnit.SECONDS)
// @Fork(1)
// @Warmup(iterations = 0) // No warmup for real-world scenario
// @Measurement(iterations = 3) // 3 runs to get average
// open class RealWorldBenchmark {
//
//  private lateinit var registry: Registry
//  private lateinit var layout: Layout
//  private lateinit var tempDir: String
//
//  private val registryUrl = System.getenv("BENCHMARK_REGISTRY_URL") ?:
// "https://192.168.3.240:5000"
//
//  @Setup(Level.Trial)
//  fun setup() {
//    println("\n🔗 Connecting to registry: $registryUrl")
//    registry = Registry.create(registryUrl = registryUrl, logLevel = KociLogLevel.INFO)
//  }
//
//  @Setup(Level.Invocation)
//  fun setupInvocation() {
//    // Fresh layout for each iteration
//    tempDir = Files.createTempDirectory("koci-bench-realworld").toString()
//    layout = Layout.create(tempDir, strictChecking = true).getOrNull()!!
//    println("📁 Created layout: $tempDir")
//  }
//
//  @TearDown(Level.Invocation)
//  fun tearDownInvocation() {
//    // Clean up after each iteration
//    Files.walk(java.nio.file.Paths.get(tempDir))
//      .sorted(Comparator.reverseOrder())
//      .forEach { Files.deleteIfExists(it) }
//  }
//
//  /**
//   * Benchmark: Pull all images from registry catalog.
//   *
//   * This mimics the Main.kt pattern:
//   * - List all repositories
//   * - Launch concurrent pulls for each
//   * - Wait for all to complete
//   */
//  @Benchmark
//  fun pullAllFromCatalog() = runBlocking {
//    var imageCount = 0
//    var totalBytes = 0L
//
//    println("\n📦 Starting pull of all images...")
//
//    registry.list().collect { result ->
//      result.getOrNull()?.let { tagsResponse ->
//        if (tagsResponse.tags.isNotEmpty()) {
//          imageCount++
//          val tag = tagsResponse.tags.first()
//
//          println("  → Pulling ${tagsResponse.name}:$tag")
//
//          launch {
//            var imageBytes = 0L
//            registry.pull(tagsResponse.name, tag, layout)
//              .collect { progress ->
//                progress.getOrNull()?.let { bytes ->
//                  imageBytes += bytes
//                  totalBytes += bytes
//                }
//              }
//            println("    ✅ ${tagsResponse.name}:$tag complete ($imageBytes bytes)")
//          }
//        }
//      }
//    }
//
//    println("\n📊 Summary:")
//    println("   Images pulled: $imageCount")
//    println("   Total bytes:   $totalBytes")
//    println("   Blobs in layout: ${layout.catalog().size}")
//  }
//
//  /**
//   * Benchmark: Pull all images, then pull them again (cache hit scenario).
//   *
//   * Tests performance when all content already exists.
//   */
//  @Benchmark
//  fun pullAllTwice() = runBlocking {
//    // First pull
//    println("\n📦 First pull (cold)...")
//    registry.list().collect { result ->
//      result.getOrNull()?.let { tagsResponse ->
//        if (tagsResponse.tags.isNotEmpty()) {
//          launch {
//            registry.pull(tagsResponse.name, tagsResponse.tags.first(), layout).collect()
//          }
//        }
//      }
//    }
//
//    // Second pull (should be fast - everything cached)
//    println("\n📦 Second pull (warm)...")
//    registry.list().collect { result ->
//      result.getOrNull()?.let { tagsResponse ->
//        if (tagsResponse.tags.isNotEmpty()) {
//          launch {
//            registry.pull(tagsResponse.name, tagsResponse.tags.first(), layout).collect()
//          }
//        }
//      }
//    }
//
//    println("\n✅ Both pulls complete")
//  }
// }
