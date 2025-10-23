#!/bin/bash
# Compare benchmark results between two git branches/commits

set -e

BASELINE_REF="${1:-main}"
NEW_REF="${2:-HEAD}"
RESULTS_DIR="benchmark-results"

echo "🔬 Comparing benchmarks: $BASELINE_REF vs $NEW_REF"
echo ""

# Create results directory
mkdir -p "$RESULTS_DIR"

# Save current branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)

# Function to run benchmarks
run_benchmarks() {
  local ref=$1
  local output_file=$2
  
  echo "📊 Running benchmarks for $ref..."
  git checkout "$ref" 2>/dev/null || git checkout -b "$ref"
  ./gradlew clean jmh | tee "$output_file"
  echo "✅ Results saved to $output_file"
  echo ""
}

# Run baseline benchmarks
run_benchmarks "$BASELINE_REF" "$RESULTS_DIR/baseline-$BASELINE_REF.txt"

# Run new version benchmarks
run_benchmarks "$NEW_REF" "$RESULTS_DIR/new-$NEW_REF.txt"

# Return to original branch
git checkout "$CURRENT_BRANCH" 2>/dev/null

echo "📈 Benchmark comparison complete!"
echo ""
echo "Results saved in $RESULTS_DIR/"
echo "  - Baseline: $RESULTS_DIR/baseline-$BASELINE_REF.txt"
echo "  - New:      $RESULTS_DIR/new-$NEW_REF.txt"
echo ""
echo "To view differences:"
echo "  diff $RESULTS_DIR/baseline-$BASELINE_REF.txt $RESULTS_DIR/new-$NEW_REF.txt"
