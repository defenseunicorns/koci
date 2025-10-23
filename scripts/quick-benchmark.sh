#!/bin/bash
# Quick benchmark script that mimics Main.kt behavior
# Pulls all images from a registry and measures total time

set -e

REGISTRY_URL="${1:-https://192.168.3.240:5000}"
LAYOUT_DIR="${2:-./benchmark-layout}"

echo "🚀 Quick Benchmark: Pull all images from registry"
echo "   Registry: $REGISTRY_URL"
echo "   Layout:   $LAYOUT_DIR"
echo ""

# Clean up old layout
rm -rf "$LAYOUT_DIR"

# Create a simple Kotlin script to run the benchmark
cat > /tmp/benchmark-main.kt << 'EOF'
import com.defenseunicorns.koci.client.Layout
import com.defenseunicorns.koci.client.Registry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

fun main() {
  val registryUrl = System.getenv("REGISTRY_URL") ?: "https://192.168.3.240:5000"
  val layoutDir = System.getenv("LAYOUT_DIR") ?: "./benchmark-layout"
  
  val layout = runBlocking {
    Layout.create(layoutDir).getOrThrow()
  }
  
  val registry = Registry.create(registryUrl = registryUrl)
  
  val totalTime = measureTimeMillis {
    runBlocking {
      var imageCount = 0
      registry.list().collect { result ->
        result.getOrNull()?.let { tagsResponse ->
          if (tagsResponse.tags.isNotEmpty()) {
            imageCount++
            println("📦 Pulling ${tagsResponse.name}:${tagsResponse.tags.first()}")
            
            val pullTime = measureTimeMillis {
              launch {
                registry.pull(tagsResponse.name, tagsResponse.tags.first(), layout)
                  .collect { progress -> 
                    // Progress updates
                  }
              }.join()
            }
            
            println("   ✅ Completed in ${pullTime}ms")
          }
        }
      }
      println("\n📊 Total images pulled: $imageCount")
    }
  }
  
  println("\n⏱️  Total time: ${totalTime}ms (${totalTime / 1000.0}s)")
  println("   Average per image: ${totalTime / kotlin.math.max(1, imageCount)}ms")
}
EOF

echo "⚙️  Compiling and running benchmark..."
echo ""

# Run with gradle
REGISTRY_URL="$REGISTRY_URL" LAYOUT_DIR="$LAYOUT_DIR" ./gradlew run --args="benchmark"

echo ""
echo "✨ Benchmark complete!"
echo "   Layout saved to: $LAYOUT_DIR"
echo ""
echo "To run again:"
echo "  ./scripts/quick-benchmark.sh $REGISTRY_URL $LAYOUT_DIR"
