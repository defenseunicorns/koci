#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Defaults
REGISTRY="localhost:5005"
ITERATIONS="10"
WARMUP="3"
USERNAME=""
PASSWORD=""
TESTS=""
COMMAND=""

usage() {
  cat <<EOF
Usage: run.sh [command] [flags]

Commands:
  bench           Run Go + v1 + current benchmarks (default if no command given)
  bench-go        Run only the Go (oras-go) benchmark
  bench-v1        Run only the Kotlin v1 (koci 0.4.3) benchmark
  bench-current   Run only the Kotlin current (koci dev) benchmark
  bench-kt        Alias for bench-current
  report          Generate report from existing results
  tests           List available test names for --tests flag

Flags:
  --registry, -r    Registry host:port (default: localhost:5005)
  --iterations, -n  Measured iterations (default: 10)
  --warmup, -w      Warm-up iterations (default: 3)
  --username, -u    Registry username (optional)
  --password, -p    Registry password (optional)
  --tests, -t       Comma-separated tests to run (default: all except push)
                    Tests: ping,catalog,resolve,tags,pull,push

Examples:
  # Run all three clients
  ./run.sh bench

  # Only metadata tests against a remote registry
  ./run.sh bench --registry 192.168.1.50:5005 --tests ping,resolve,tags,catalog

  # Only pull
  ./run.sh bench --tests pull
EOF
  exit 0
}

# Parse command (first positional arg)
if [[ $# -gt 0 ]] && [[ "$1" != -* ]]; then
  COMMAND="$1"; shift
fi

# Parse flags
while [[ $# -gt 0 ]]; do
  case $1 in
    --registry|-r)    REGISTRY="$2"; shift 2 ;;
    --iterations|-n)  ITERATIONS="$2"; shift 2 ;;
    --warmup|-w)      WARMUP="$2"; shift 2 ;;
    --username|-u)    USERNAME="$2"; shift 2 ;;
    --password|-p)    PASSWORD="$2"; shift 2 ;;
    --tests|-t)       TESTS="$2"; shift 2 ;;
    --help|-h)        usage ;;
    *) echo "Unknown flag: $1"; usage ;;
  esac
done

COMMAND="${COMMAND:-bench}"

echo "=== Benchmark Configuration ==="
echo "Command:    $COMMAND"
echo "Registry:   $REGISTRY"
echo "Iterations: $ITERATIONS"
echo "Warmup:     $WARMUP"
if [[ -n "$USERNAME" ]]; then echo "Auth:       $USERNAME:****"; fi
echo ""

is_local() {
  [[ "$REGISTRY" == localhost:* ]] || [[ "$REGISTRY" == 127.0.0.1:* ]]
}

# Determine registry URL for Kotlin (needs http:// prefix)
kotlin_registry() {
  if [[ "$REGISTRY" == http://* ]] || [[ "$REGISTRY" == https://* ]]; then
    echo "$REGISTRY"
  elif is_local; then
    echo "http://$REGISTRY"
  else
    echo "https://$REGISTRY"
  fi
}

do_build() {
  echo "=== Building koci ==="
  cd "$PROJECT_ROOT" && ./gradlew :bench:harness:jar :bench:v1:jar :bench:current:jar
  echo "=== Building Go benchmark ==="
  cd "$SCRIPT_DIR/go" && go build -o bench-go .
  mkdir -p "$SCRIPT_DIR/results"
}

do_bench_go() {
  do_build
  echo ""
  echo "=== Running Go (oras-go) benchmark ==="
  local GO_REGISTRY
  GO_REGISTRY=$(kotlin_registry)
  local go_args=(-registry "$GO_REGISTRY" -iterations "$ITERATIONS" -warmup "$WARMUP")
  if [[ -n "$USERNAME" ]]; then go_args+=(-username "$USERNAME" -password "$PASSWORD"); fi
  if [[ -n "$TESTS" ]]; then go_args+=(-tests "$TESTS"); fi
  "$SCRIPT_DIR/go/bench-go" "${go_args[@]}" > "$SCRIPT_DIR/results/oras-go.json" || {
    echo "Go benchmark failed (exit $?), check stderr above"
  }
  echo "Go results saved."
}

do_bench_v1() {
  do_build
  echo ""
  echo "=== Running Kotlin v1 (koci 0.4.3) benchmark ==="
  local KT_REGISTRY
  KT_REGISTRY=$(kotlin_registry)
  local kt_args="--registry $KT_REGISTRY --iterations $ITERATIONS --warmup $WARMUP"
  kt_args="$kt_args --output $SCRIPT_DIR/results/koci-v1.json"
  if [[ -n "$USERNAME" ]]; then kt_args="$kt_args --username $USERNAME --password $PASSWORD"; fi
  if [[ -n "$TESTS" ]]; then kt_args="$kt_args --tests $TESTS"; fi
  cd "$PROJECT_ROOT" && ./gradlew :bench:v1:bench \
    -PbenchArgs="$kt_args" \
    --console=plain || {
    echo "v1 benchmark failed (exit $?), check stderr above"
  }
  echo "v1 results saved."
}

do_bench_current() {
  do_build
  echo ""
  echo "=== Running Kotlin current (koci dev) benchmark ==="
  local KT_REGISTRY
  KT_REGISTRY=$(kotlin_registry)
  local kt_args="--registry $KT_REGISTRY --iterations $ITERATIONS --warmup $WARMUP"
  kt_args="$kt_args --output $SCRIPT_DIR/results/koci-current.json"
  if [[ -n "$USERNAME" ]]; then kt_args="$kt_args --username $USERNAME --password $PASSWORD"; fi
  if [[ -n "$TESTS" ]]; then kt_args="$kt_args --tests $TESTS"; fi
  cd "$PROJECT_ROOT" && ./gradlew :bench:current:bench \
    -PbenchArgs="$kt_args" \
    --console=plain || {
    echo "current benchmark failed (exit $?), check stderr above"
  }
  echo "current results saved."
}

do_report() {
  echo ""
  echo "=== Generating report ==="
  python3 "$SCRIPT_DIR/report.py"
  echo ""
  echo "Report: $SCRIPT_DIR/results/report.md"
}

do_bench() {
  do_bench_go
  do_bench_v1
  do_bench_current
  do_report
}

do_list_tests() {
  cat <<EOF
Available tests (pass comma-separated to --tests):

  ping      GET /v2/ round-trip
  catalog   GET /v2/_catalog
  resolve   HEAD + GET manifest (tag to descriptor)
  tags      List tags for a repo
  pull      Full pull to local store
  push      Push unique 5/50/500/1000MB blobs (must be specified explicitly)

Examples:
  --tests ping,resolve,tags
  --tests pull
  --tests push
EOF
}

case "$COMMAND" in
  bench)          do_bench ;;
  bench-go)       do_bench_go; do_report ;;
  bench-v1)       do_bench_v1; do_report ;;
  bench-current)  do_bench_current; do_report ;;
  bench-kt)       do_bench_current; do_report ;;
  report)         do_report ;;
  tests)          do_list_tests ;;
  *)              echo "Unknown command: $COMMAND"; usage ;;
esac
