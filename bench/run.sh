#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Defaults
REGISTRY="localhost:5005"
ITERATIONS="10"
WARMUP="3"
SIZES="1mb,50mb,500mb"
USERNAME=""
PASSWORD=""
DISCOVER=""
TESTS=""
COMMAND=""

usage() {
  cat <<EOF
Usage: run.sh [command] [flags]

Commands:
  setup       Seed the registry only (run on the registry machine)
  bench       Run both Go + Kotlin benchmarks (default if no command given)
  bench-go    Run only the Go (oras-go) benchmark
  bench-kt    Run only the Kotlin (koci) benchmark
  report      Generate report from existing results
  reset       Wipe the local registry and restart fresh
  tests       List available test names for --tests flag
  all         Setup + bench

Flags:
  --registry, -r    Registry host:port (default: localhost:5005)
  --iterations, -n  Measured iterations (default: 10)
  --warmup, -w      Warm-up iterations (default: 3)
  --sizes, -s       Comma-separated sizes (default: 1mb,50mb,500mb,2gb)
  --username, -u    Registry username (optional)
  --password, -p    Registry password (optional)
  --discover        Auto-discover repos from registry (no seeding needed)
  --tests, -t       Comma-separated tests to run (default: all)
                    Tests: ping,catalog,resolve,tags,pull,push,
                           multitag-list,multitag-process,
                           parallel-seq,parallel-conc,
                           discovery-list,discovery-full

Examples:
  # Run everything
  ./run.sh all

  # Only metadata tests
  ./run.sh bench --registry 192.168.1.50:5005 --tests ping,resolve,tags,catalog

  # Only pull/push
  ./run.sh bench --tests pull,push --sizes 1mb,50mb

  # Only discovery flow
  ./run.sh bench --tests discovery-list,discovery-full
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
    --sizes|-s)       SIZES="$2"; shift 2 ;;
    --username|-u)    USERNAME="$2"; shift 2 ;;
    --password|-p)    PASSWORD="$2"; shift 2 ;;
    --discover)       DISCOVER="true"; shift ;;
    --tests|-t)       TESTS="$2"; shift 2 ;;
    --help|-h)        usage ;;
    *) echo "Unknown flag: $1"; usage ;;
  esac
done

COMMAND="${COMMAND:-all}"

echo "=== Benchmark Configuration ==="
echo "Command:    $COMMAND"
echo "Registry:   $REGISTRY"
echo "Iterations: $ITERATIONS"
echo "Warmup:     $WARMUP"
echo "Sizes:      $SIZES"
if [[ -n "$USERNAME" ]]; then echo "Auth:       $USERNAME:****"; fi
if [[ "$DISCOVER" == "true" ]]; then echo "Discover:   yes"; fi
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

detect_arch() {
  local arch
  arch=$(uname -m)
  case "$arch" in
    x86_64)  echo "amd64" ;;
    aarch64|arm64) echo "arm64" ;;
    *) echo "$arch" ;;
  esac
}

detect_os() {
  local os
  os=$(uname -s | tr '[:upper:]' '[:lower:]')
  case "$os" in
    darwin) echo "darwin" ;;
    linux)  echo "linux" ;;
    *) echo "$os" ;;
  esac
}

title_case() {
  local first rest
  first=$(printf %.1s "$1" | tr '[:lower:]' '[:upper:]')
  rest=$(printf %s "$1" | cut -c 2-)
  printf '%s%s' "$first" "$rest"
}

install_deps() {
  local arch os oras_version zarf_version
  arch=$(detect_arch)
  os=$(detect_os)
  oras_version="1.2.2"
  zarf_version="latest"

  cd "$PROJECT_ROOT"
  mkdir -p bin

  # Install oras if missing
  if [[ ! -x bin/oras ]]; then
    echo "Installing oras v${oras_version} (${os}/${arch})..."
    curl -sLO "https://github.com/oras-project/oras/releases/download/v${oras_version}/oras_${oras_version}_${os}_${arch}.tar.gz"
    mkdir -p oras-install
    tar -zxf "oras_${oras_version}_${os}_${arch}.tar.gz" -C oras-install/
    mv oras-install/oras bin/
    rm -rf "oras_${oras_version}_${os}_${arch}.tar.gz" oras-install
  fi

  # Install zarf if missing
  if [[ ! -x bin/zarf ]]; then
    if [[ "$zarf_version" == "latest" ]]; then
      zarf_version=$(curl -sIX HEAD https://github.com/zarf-dev/zarf/releases/latest | grep -i ^location: | grep -Eo 'v[0-9]+.[0-9]+.[0-9]+')
    fi
    echo "Installing zarf ${zarf_version} (${os}/${arch})..."
    curl -sL "https://github.com/zarf-dev/zarf/releases/download/${zarf_version}/zarf_${zarf_version}_$(title_case "$os")_${arch}" -o bin/zarf
    chmod +x bin/zarf
  fi
}

do_setup() {
  # Start local registry if targeting localhost
  if is_local; then
    echo "=== Starting local registry ==="
    cd "$PROJECT_ROOT" && docker compose up -d
    echo "Waiting for registry..."
    for i in $(seq 1 30); do
      if curl -sf http://localhost:5005/v2/ > /dev/null 2>&1; then
        echo "Registry is ready"
        break
      fi
      if [[ $i -eq 30 ]]; then
        echo "Registry failed to start"; exit 1
      fi
      sleep 1
    done
  fi

  # Install deps (oras + zarf, no Go required)
  echo "=== Installing dependencies ==="
  install_deps

  # Seed registry
  echo "=== Seeding benchmark artifacts ==="
  local seed_args=(--registry "$REGISTRY" --sizes "$SIZES")
  if [[ -n "$USERNAME" ]]; then seed_args+=(--username "$USERNAME" --password "$PASSWORD"); fi
  bash "$SCRIPT_DIR/seed.sh" "${seed_args[@]}"
}

gen_push_blobs() {
  # Generate unique push blobs per client if missing
  IFS=',' read -ra sizes <<< "$SIZES"
  for label in "${sizes[@]}"; do
    local bytes
    case "$label" in
      1mb) bytes=1 ;; 50mb) bytes=50 ;; 500mb) bytes=500 ;; *) continue ;;
    esac
    for client in go kt; do
      local f="/tmp/bench-push-${client}-${label}.bin"
      if [[ ! -f "$f" ]]; then
        echo "Generating $client push blob ($label)..."
        dd if=/dev/urandom of="$f" bs=1048576 count=$bytes 2>/dev/null
      fi
    done
  done
}

do_build() {
  echo "=== Building koci ==="
  cd "$PROJECT_ROOT" && ./gradlew jar
  echo "=== Building Go benchmark ==="
  cd "$SCRIPT_DIR/go" && go build -o bench-go .
  mkdir -p "$SCRIPT_DIR/results"
  gen_push_blobs
}

do_bench_go() {
  do_build
  echo ""
  echo "=== Running Go benchmark ==="
  local GO_REGISTRY
  GO_REGISTRY=$(kotlin_registry)
  local go_args=(-registry "$GO_REGISTRY" -iterations "$ITERATIONS" -warmup "$WARMUP" -sizes "$SIZES")
  if [[ -n "$USERNAME" ]]; then go_args+=(-username "$USERNAME" -password "$PASSWORD"); fi
  if [[ "$DISCOVER" == "true" ]]; then go_args+=(-discover); fi
  if [[ -n "$TESTS" ]]; then go_args+=(-tests "$TESTS"); fi
  "$SCRIPT_DIR/go/bench-go" "${go_args[@]}" > "$SCRIPT_DIR/results/oras-go.json" || {
    echo "Go benchmark failed (exit $?), check stderr above"
  }
  echo "Go results saved."
}

do_bench_kt() {
  do_build
  echo ""
  echo "=== Running Kotlin benchmark ==="
  local KOTLIN_REGISTRY
  KOTLIN_REGISTRY=$(kotlin_registry)
  local kt_args="--registry $KOTLIN_REGISTRY --iterations $ITERATIONS --warmup $WARMUP --sizes $SIZES"
  kt_args="$kt_args --output $SCRIPT_DIR/results/koci.json"
  if [[ -n "$USERNAME" ]]; then kt_args="$kt_args --username $USERNAME --password $PASSWORD"; fi
  if [[ "$DISCOVER" == "true" ]]; then kt_args="$kt_args --discover"; fi
  if [[ -n "$TESTS" ]]; then kt_args="$kt_args --tests $TESTS"; fi
  cd "$SCRIPT_DIR/kotlin" && ../../gradlew bench \
    -PbenchArgs="$kt_args" \
    --console=plain || {
    echo "Kotlin benchmark failed (exit $?), check stderr above"
  }
  echo "Kotlin results saved."
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
  do_bench_kt
  do_report
}

do_reset() {
  echo "=== Resetting local registry ==="
  cd "$PROJECT_ROOT"
  docker compose down 2>/dev/null || true
  rm -rf .registry 2>/dev/null || true
  echo "Registry data wiped."
}

do_list_tests() {
  cat <<EOF
Available tests (pass comma-separated to --tests):

  ping              GET /v2/ round-trip
  catalog           GET /v2/_catalog
  resolve           HEAD + GET manifest (tag to descriptor)
  tags              List tags for a repo
  pull              Full pull to local store (per size)
  push              Single-shot upload (per size, seeded only)
  multitag-list     List tags on a many-tag repo
  multitag-process  List + resolve + manifest per tag
  parallel-seq      Pull N packages sequentially
  parallel-conc     Pull N packages concurrently
  discovery-list    catalog + sequential tags per repo
  discovery-full    list + resolve + manifest per repo

Examples:
  --tests ping,resolve,tags
  --tests pull,push
  --tests multitag-process,discovery-full
EOF
}

case "$COMMAND" in
  setup)    do_setup ;;
  bench)    do_bench ;;
  bench-go) do_bench_go; do_report ;;
  bench-kt) do_bench_kt; do_report ;;
  report)   do_report ;;
  reset)    do_reset ;;
  tests)    do_list_tests ;;
  all)      do_setup; do_bench ;;
  *)        echo "Unknown command: $COMMAND"; usage ;;
esac
