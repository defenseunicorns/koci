///*
// * Copyright 2024-2025 Defense Unicorns
// * SPDX-License-Identifier: Apache-2.0
// */
//
//package com.defenseunicorns.koci
//
//import com.defenseunicorns.koci.client.TransferCoordinator
//import com.defenseunicorns.koci.createKociLogger
//import com.defenseunicorns.koci.models.content.Descriptor
//import com.defenseunicorns.koci.models.content.Digest
//import com.defenseunicorns.koci.models.content.RegisteredAlgorithm
//import com.defenseunicorns.koci.models.errors.OCIResult
//import java.util.concurrent.TimeUnit
//import kotlinx.coroutines.async
//import kotlinx.coroutines.awaitAll
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.Flow
//import kotlinx.coroutines.flow.collect
//import kotlinx.coroutines.flow.flow
//import kotlinx.coroutines.runBlocking
//import org.openjdk.jmh.annotations.Benchmark
//import org.openjdk.jmh.annotations.BenchmarkMode
//import org.openjdk.jmh.annotations.Fork
//import org.openjdk.jmh.annotations.Measurement
//import org.openjdk.jmh.annotations.Mode
//import org.openjdk.jmh.annotations.OutputTimeUnit
//import org.openjdk.jmh.annotations.Scope
//import org.openjdk.jmh.annotations.Setup
//import org.openjdk.jmh.annotations.State
//import org.openjdk.jmh.annotations.Warmup
//
///**
// * Benchmark for TransferCoordinator to measure performance improvements.
// *
// * Tests concurrent transfers of the same descriptor to verify that:
// * - Only one actual transfer occurs
// * - Other requests wait efficiently
// * - Cleanup happens quickly without artificial delays
// *
// * Run with: ./gradlew jmh
// */
//@State(Scope.Benchmark)
//@BenchmarkMode(Mode.Throughput)
//@OutputTimeUnit(TimeUnit.SECONDS)
//@Fork(1)
//@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
//@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
//open class TransferCoordinatorBenchmark {
//
//  private lateinit var coordinator: TransferCoordinator
//  private lateinit var testDescriptor: Descriptor
//
//  @Setup
//  fun setup() {
//    coordinator = TransferCoordinator(createKociLogger(KociLogLevel.NONE, "Benchmark"))
//
//    // Create a test descriptor
//    val content = "test-content"
//    testDescriptor = Descriptor(
//      mediaType = "application/test",
//      digest = Digest(
//        RegisteredAlgorithm.SHA256,
//        RegisteredAlgorithm.SHA256.hasher().apply { update(content.toByteArray()) }.digest()
//      ),
//      size = content.length.toLong()
//    )
//  }
//
//  /**
//   * Simulates a transfer operation that takes some time.
//   */
//  private fun simulateTransfer(): Flow<OCIResult<Int>> = flow {
//    // Simulate network I/O with small delay
//    delay(10)
//    emit(OCIResult.ok(100))
//  }
//
//  /**
//   * Benchmark: Single transfer with no concurrency.
//   * Baseline for comparison.
//   */
//  @Benchmark
//  fun singleTransfer() = runBlocking {
//    coordinator.transfer(testDescriptor) { simulateTransfer() }.collect()
//  }
//
//  /**
//   * Benchmark: 10 concurrent transfers of the same descriptor.
//   * Only one should actually execute, others should wait.
//   */
//  @Benchmark
//  fun concurrentTransfersSameDescriptor() = runBlocking {
//    val jobs = (1..10).map {
//      async {
//        coordinator.transfer(testDescriptor) { simulateTransfer() }.collect()
//      }
//    }
//    jobs.awaitAll()
//  }
//
//  /**
//   * Benchmark: 10 concurrent transfers of different descriptors.
//   * All should execute in parallel.
//   */
//  @Benchmark
//  fun concurrentTransfersDifferentDescriptors() = runBlocking {
//    val jobs = (1..10).map { i ->
//      val content = "test-content-$i"
//      val descriptor = Descriptor(
//        mediaType = "application/test",
//        digest = Digest(
//          RegisteredAlgorithm.SHA256,
//          RegisteredAlgorithm.SHA256.hasher().apply { update(content.toByteArray()) }.digest()
//        ),
//        size = content.length.toLong()
//      )
//
//      async {
//        coordinator.transfer(descriptor) { simulateTransfer() }.collect()
//      }
//    }
//    jobs.awaitAll()
//  }
//
//  /**
//   * Benchmark: Sequential transfers to measure cleanup overhead.
//   * Tests that cleanup is fast and doesn't accumulate delays.
//   */
//  @Benchmark
//  fun sequentialTransfers() = runBlocking {
//    repeat(10) {
//      coordinator.transfer(testDescriptor) { simulateTransfer() }.collect()
//    }
//  }
//}
