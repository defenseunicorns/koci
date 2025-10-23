///*
// * Copyright 2024-2025 Defense Unicorns
// * SPDX-License-Identifier: Apache-2.0
// */
//
//package com.defenseunicorns.koci
//
//import com.defenseunicorns.koci.client.Layout
//import com.defenseunicorns.koci.models.content.Descriptor
//import com.defenseunicorns.koci.models.content.Digest
//import com.defenseunicorns.koci.models.content.RegisteredAlgorithm
//import java.io.ByteArrayInputStream
//import java.nio.file.Files
//import java.util.concurrent.TimeUnit
//import kotlinx.coroutines.async
//import kotlinx.coroutines.awaitAll
//import kotlinx.coroutines.flow.collect
//import kotlinx.coroutines.runBlocking
//import org.openjdk.jmh.annotations.Benchmark
//import org.openjdk.jmh.annotations.BenchmarkMode
//import org.openjdk.jmh.annotations.Fork
//import org.openjdk.jmh.annotations.Level
//import org.openjdk.jmh.annotations.Measurement
//import org.openjdk.jmh.annotations.Mode
//import org.openjdk.jmh.annotations.OutputTimeUnit
//import org.openjdk.jmh.annotations.Scope
//import org.openjdk.jmh.annotations.Setup
//import org.openjdk.jmh.annotations.State
//import org.openjdk.jmh.annotations.TearDown
//import org.openjdk.jmh.annotations.Warmup
//
///**
// * Benchmark for Layout push operations with staging directory.
// *
// * Tests the performance impact of:
// * - Writing to staging directory
// * - Verification (size + digest)
// * - Atomic move/copy to blobs directory
// * - Concurrent pushes of the same content
// *
// * Run with: ./gradlew jmh --includes=LayoutPushBenchmark
// */
//@State(Scope.Benchmark)
//@BenchmarkMode(Mode.Throughput)
//@OutputTimeUnit(TimeUnit.SECONDS)
//@Fork(1)
//@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
//@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
//open class LayoutPushBenchmark {
//
//  private lateinit var layout: Layout
//  private lateinit var tempDir: String
//  private lateinit var smallBlob: Pair<Descriptor, ByteArray>
//  private lateinit var mediumBlob: Pair<Descriptor, ByteArray>
//
//  @Setup(Level.Trial)
//  fun setup() {
//    // Create temporary directory for layout
//    tempDir = Files.createTempDirectory("koci-bench").toString()
//
//    layout = runBlocking {
//      Layout.create(tempDir, strictChecking = true).getOrThrow()
//    }
//
//    // Create test blobs of different sizes
//    smallBlob = createTestBlob(1024) // 1KB
//    mediumBlob = createTestBlob(100 * 1024) // 100KB
//  }
//
//  @TearDown(Level.Trial)
//  fun tearDown() {
//    // Clean up temporary directory
//    Files.walk(java.nio.file.Paths.get(tempDir))
//      .sorted(Comparator.reverseOrder())
//      .forEach { Files.deleteIfExists(it) }
//  }
//
//  private fun createTestBlob(size: Int): Pair<Descriptor, ByteArray> {
//    val content = ByteArray(size) { (it % 256).toByte() }
//    val hasher = RegisteredAlgorithm.SHA256.hasher()
//    hasher.update(content)
//
//    val descriptor = Descriptor(
//      mediaType = "application/octet-stream",
//      digest = Digest(RegisteredAlgorithm.SHA256, hasher.digest()),
//      size = content.size.toLong()
//    )
//
//    return descriptor to content
//  }
//
//  /**
//   * Benchmark: Push a small blob (1KB).
//   * Tests overhead of staging + verification + move.
//   */
//  @Benchmark
//  fun pushSmallBlob() = runBlocking {
//    val (descriptor, content) = smallBlob
//    layout.push(descriptor, ByteArrayInputStream(content)).collect()
//    layout.remove(descriptor)
//  }
//
//  /**
//   * Benchmark: Push a medium blob (100KB).
//   * Tests throughput with more realistic blob sizes.
//   */
//  @Benchmark
//  fun pushMediumBlob() = runBlocking {
//    val (descriptor, content) = mediumBlob
//    layout.push(descriptor, ByteArrayInputStream(content)).collect()
//    layout.remove(descriptor)
//  }
//
//  /**
//   * Benchmark: 5 concurrent pushes of the same blob.
//   * Tests coordinator efficiency - only one should actually write.
//   */
//  @Benchmark
//  fun concurrentPushSameBlob() = runBlocking {
//    val (descriptor, content) = mediumBlob
//
//    val jobs = (1..5).map {
//      async {
//        layout.push(descriptor, ByteArrayInputStream(content)).collect()
//      }
//    }
//
//    jobs.awaitAll()
//    layout.remove(descriptor)
//  }
//
//  /**
//   * Benchmark: 5 concurrent pushes of different blobs.
//   * Tests parallel write performance with staging.
//   */
//  @Benchmark
//  fun concurrentPushDifferentBlobs() = runBlocking {
//    val blobs = (1..5).map { createTestBlob(50 * 1024) } // 5x 50KB blobs
//
//    val jobs = blobs.map { (descriptor, content) ->
//      async {
//        layout.push(descriptor, ByteArrayInputStream(content)).collect()
//      }
//    }
//
//    jobs.awaitAll()
//
//    // Cleanup
//    blobs.forEach { (descriptor, _) ->
//      layout.remove(descriptor)
//    }
//  }
//
//  /**
//   * Benchmark: Sequential pushes to measure cleanup overhead.
//   * Tests that staging cleanup doesn't accumulate delays.
//   */
//  @Benchmark
//  fun sequentialPushes() = runBlocking {
//    val blobs = (1..10).map { createTestBlob(10 * 1024) } // 10x 10KB blobs
//
//    blobs.forEach { (descriptor, content) ->
//      layout.push(descriptor, ByteArrayInputStream(content)).collect()
//      layout.remove(descriptor)
//    }
//  }
//}
