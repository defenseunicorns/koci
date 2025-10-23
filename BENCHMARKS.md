# Performance Benchmarks

This directory contains JMH (Java Microbenchmark Harness) benchmarks to measure the performance of key KOCI operations.

## Running Benchmarks

### Run all benchmarks
```bash
./gradlew jmh
```

### Run specific benchmark
```bash
./gradlew jmh --includes=TransferCoordinatorBenchmark
./gradlew jmh --includes=LayoutPushBenchmark
```

### Run with custom parameters
```bash
./gradlew jmh -Pjmh.iterations=5 -Pjmh.fork=2
```

## Benchmark Suites

### RealWorldBenchmark (Integration - Main.kt Pattern)
Mimics the actual Main.kt usage pattern - pulls all images from registry:

- **pullAllFromCatalog**: List catalog and pull all images concurrently (like Main.kt)
- **pullAllTwice**: Pull all images twice (tests cold vs warm cache performance)

**Configuration:**
```bash
export BENCHMARK_REGISTRY_URL="https://192.168.3.240:5000"
./gradlew jmh --includes=RealWorldBenchmark
```

**Key Metrics:**
- Single-shot time (seconds) - total time to pull all images
- Shows real-world performance with multiple concurrent transfers
- Tests full system: catalog → resolve → pull → coordinate → stage → verify → move
- Second pull should be significantly faster (cache hits)

**Best for:**
- Comparing overall performance between versions
- Testing under realistic concurrent load
- Measuring impact of staging + coordination changes

### RegistryPullBenchmark (Integration - Per-Image)
Per-image benchmark pulling from an actual registry:

- **singlePull**: Complete image pull from registry
- **concurrentPullsSameImage**: 3 concurrent pulls of same image (tests deduplication)
- **pullWithCache**: Pull with all content already cached (tests exists() performance)
- **listAndPull**: Full workflow from catalog listing to pull

**Configuration:**
Set environment variables to customize:
```bash
export BENCHMARK_REGISTRY_URL="https://192.168.3.240:5000"
export BENCHMARK_REPO="core-base"
export BENCHMARK_TAG="0.35.0-upstream"
./gradlew jmh --includes=RegistryPullBenchmark
```

**Key Metrics:**
- Average time (ms) - lower is better
- Concurrent pulls should be ~3x faster than 3 sequential pulls
- Cached pulls should be near-instant (< 100ms)
- Tests full pipeline: network + staging + verification + move

**Note:** Results vary based on:
- Network latency to registry
- Registry performance
- Image size
- Disk I/O speed

### TransferCoordinatorBenchmark
Tests the performance of the transfer coordination system:

- **singleTransfer**: Baseline single transfer with no concurrency
- **concurrentTransfersSameDescriptor**: 10 concurrent requests for the same content (only 1 actual transfer)
- **concurrentTransfersDifferentDescriptors**: 10 concurrent transfers of different content (all execute)
- **sequentialTransfers**: 10 sequential transfers to measure cleanup overhead

**Key Metrics:**
- Throughput (ops/sec) - higher is better
- Should show minimal overhead for coordination
- Sequential transfers should not accumulate delays (no 1s delay per transfer)

### LayoutPushBenchmark
Tests the performance of Layout push operations with staging:

- **pushSmallBlob**: Push 1KB blob (tests overhead)
- **pushMediumBlob**: Push 100KB blob (tests throughput)
- **concurrentPushSameBlob**: 5 concurrent pushes of same blob (tests deduplication)
- **concurrentPushDifferentBlobs**: 5 concurrent pushes of different blobs (tests parallelism)
- **sequentialPushes**: 10 sequential pushes (tests cleanup efficiency)

**Key Metrics:**
- Throughput (ops/sec) - higher is better
- Staging overhead should be minimal
- Concurrent same-blob pushes should be nearly as fast as single push
- No accumulated delays from cleanup

## Comparing Versions

To compare performance between versions:

1. **Baseline (old version)**:
   ```bash
   git checkout main
   ./gradlew jmh > baseline-results.txt
   ```

2. **New version (with changes)**:
   ```bash
   git checkout your-branch
   ./gradlew jmh > new-results.txt
   ```

3. **Compare results**:
   ```bash
   diff baseline-results.txt new-results.txt
   ```

## Expected Improvements

The new implementation should show:

1. **TransferCoordinator**: ~1000x faster sequential transfers (removed 1s delay)
2. **Layout Push**: Minimal overhead from staging (< 5% slower than direct write)
3. **Concurrent Operations**: Near-linear scaling with different descriptors
4. **Cleanup**: No accumulated delays in sequential operations

## Interpreting Results

JMH outputs results in this format:
```
Benchmark                                          Mode  Cnt   Score   Error  Units
TransferCoordinatorBenchmark.singleTransfer       thrpt    3  95.234 ± 2.456  ops/s
```

- **Mode**: `thrpt` = throughput (operations per second)
- **Cnt**: Number of measurement iterations
- **Score**: Average throughput
- **Error**: Margin of error (±)
- **Units**: Operations per second

Higher scores are better for throughput mode.

## Notes

- Benchmarks use `strictChecking = true` to test worst-case performance
- Small delays (10ms) simulate network I/O in TransferCoordinator tests
- Temporary directories are cleaned up after each trial
- Results may vary based on disk I/O performance (SSD vs HDD)

## Quick Start

### Run all benchmarks (unit + integration)
```bash
./gradlew jmh
```

### Run only unit benchmarks (no network)
```bash
./gradlew jmh --includes="TransferCoordinator|LayoutPush"
```

### Run only integration benchmarks (with registry)
```bash
export BENCHMARK_REGISTRY_URL="https://192.168.3.240:5000"
./gradlew jmh --includes=RegistryPullBenchmark
```

### Compare two versions
```bash
./scripts/benchmark-compare.sh main feature-branch
```

### Generate JSON output for analysis
```bash
./gradlew jmh -Pjmh.resultsFormat=json -Pjmh.resultsFile=results.json
```
