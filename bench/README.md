# koci Benchmark Suite

Compares three OCI client implementations against the same registry:

| Client | Language | Version |
|--------|----------|---------|
| **oras-go** | Go | oras-go v2 |
| **koci v1** | Kotlin/JVM | 0.4.3 (published) |
| **koci current** | Kotlin/JVM | local dev build |

## Prerequisites

- Docker (registry must be running and reachable)
- Go 1.21+
- JDK 21+
- Python 3.9+

## Test environment

The benchmark discovers every repo in the registry via `_catalog` and runs all tests against whatever it finds. You are responsible for populating the registry with the packages you want to measure before running.

Point the benchmark at a dedicated registry you control — do not run it against a shared or production registry.

**Local registry** (via the project's Docker Compose):

```bash
docker compose up -d   # starts registry on localhost:5005
```

Then push whatever OCI artifacts you want to test against using any OCI-compatible tool (`oras`, `docker`, `crane`, etc.):

```bash
oras push localhost:5005/my-app:v1 ./artifact.tar
```

## Running benchmarks

```bash
# Build + run all three clients + generate report
./run.sh bench
```

Individual clients:

```bash
./run.sh bench-go       # oras-go only
./run.sh bench-v1       # koci 0.4.3 only
./run.sh bench-current  # koci dev only
```

Regenerate the report from existing result files without re-running:

```bash
./run.sh report
```

### Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--registry`, `-r` | `localhost:5005` | Registry host:port (or full URL) |
| `--iterations`, `-n` | `10` | Measured iterations per operation |
| `--warmup`, `-w` | `3` | Warm-up iterations (discarded) |
| `--username`, `-u` | | Registry username |
| `--password`, `-p` | | Registry password |
| `--tests`, `-t` | all except push | Comma-separated tests to run |

### Available tests

| Test | Default | Description |
|------|---------|-------------|
| `ping` | yes | `GET /v2/` round-trip |
| `catalog` | yes | `GET /v2/_catalog` |
| `resolve` | yes | Tag → descriptor resolution |
| `tags` | yes | List tags for a repo |
| `pull` | yes | Full pull to a temp store, across all discovered repos |
| `push` | **no** | Upload unique blobs (must opt in — see below) |

### Push benchmark

Push is excluded from the default test suite because it uploads gigabytes of data. Enable it explicitly:

```bash
./run.sh bench --tests push
./run.sh bench --tests pull,push
```

Each client pushes blobs of 5 MB, 50 MB, 500 MB, and 1000 MB to fresh repos. The registry does not need any pre-existing content for push — each run generates its own unique payloads.

## Results

Results are written to `bench/results/`:

| File | Contents |
|------|----------|
| `oras-go.json` | Raw measurements from the Go client |
| `koci-v1.json` | Raw measurements from koci 0.4.3 |
| `koci-current.json` | Raw measurements from the dev build |
| `report.md` | Generated comparison report |

The report is also printed to stdout at the end of each bench run.
