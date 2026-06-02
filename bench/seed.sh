#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Defaults
REGISTRY="localhost:5005"
SIZES="1mb,50mb,500mb,2gb"
TAG_COUNT=100
USERNAME=""
PASSWORD=""

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --registry|-r) REGISTRY="$2"; shift 2 ;;
    --sizes|-s)    SIZES="$2"; shift 2 ;;
    --tag-count)   TAG_COUNT="$2"; shift 2 ;;
    --username|-u) USERNAME="$2"; shift 2 ;;
    --password|-p) PASSWORD="$2"; shift 2 ;;
    *) echo "Unknown flag: $1"; exit 1 ;;
  esac
done

# Detect scheme: explicit http:// = HTTP, anything else = HTTPS
if [[ "$REGISTRY" == http://* ]]; then
  SCHEME="http"
  REGISTRY="${REGISTRY#http://}"
elif [[ "$REGISTRY" == https://* ]]; then
  SCHEME="https"
  REGISTRY="${REGISTRY#https://}"
elif [[ "$REGISTRY" == localhost:* ]] || [[ "$REGISTRY" == 127.0.0.1:* ]]; then
  SCHEME="http"
else
  SCHEME="https"
fi
BASE_URL="${SCHEME}://${REGISTRY}"

# Build curl auth flag
CURL_AUTH=""
if [[ -n "$USERNAME" ]] && [[ -n "$PASSWORD" ]]; then
  CURL_AUTH="-u ${USERNAME}:${PASSWORD}"
fi

size_to_bytes() {
  case "$1" in
    1mb)   echo 1048576 ;;
    50mb)  echo 52428800 ;;
    500mb) echo 524288000 ;;
    *)     echo "" ;;
  esac
}

push_blob() {
  local repo="$1" blob_file="$2" dgst="$3" size="$4"
  local upload_url
  upload_url=$(curl -s $CURL_AUTH -X POST -D - -o /dev/null \
    "${BASE_URL}/v2/${repo}/blobs/uploads/" \
    | grep -i "^location:" | tr -d '\r' | awk '{print $2}')
  if [[ "$upload_url" == /* ]]; then
    upload_url="${BASE_URL}${upload_url}"
  fi
  local sep="?"
  if [[ "$upload_url" == *"?"* ]]; then sep="&"; fi
  curl -s $CURL_AUTH -X PUT \
    -H "Content-Type: application/octet-stream" \
    -H "Content-Length: ${size}" \
    -T "${blob_file}" \
    "${upload_url}${sep}digest=${dgst}" > /dev/null
}

manifest_exists() {
  local status
  status=$(curl -s $CURL_AUTH -o /dev/null -w "%{http_code}" "${BASE_URL}/v2/$1/manifests/$2" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json")
  [[ "$status" == "200" ]]
}

# Shared config blob
config_file="/tmp/bench-config.json"
printf '{}' > "$config_file"
config_digest="sha256:$(shasum -a 256 "$config_file" | awk '{print $1}')"
config_size=$(stat -f%z "$config_file" 2>/dev/null || stat -c%s "$config_file" 2>/dev/null)

push_oci_artifact() {
  local repo="$1" tag="$2" blob_file="$3" blob_digest="$4" blob_size="$5"
  push_blob "$repo" "$blob_file" "$blob_digest" "$blob_size"
  push_blob "$repo" "$config_file" "$config_digest" "$config_size"
  local manifest="{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"${config_digest}\",\"size\":${config_size}},\"layers\":[{\"mediaType\":\"application/octet-stream\",\"digest\":\"${blob_digest}\",\"size\":${blob_size}}]}"
  curl -s $CURL_AUTH -X PUT \
    -H "Content-Type: application/vnd.oci.image.manifest.v1+json" \
    -d "$manifest" \
    "${BASE_URL}/v2/${repo}/manifests/${tag}" > /dev/null
}

IFS=',' read -ra REQUESTED_SIZES <<< "$SIZES"

# ============================================================
# 1. OCI artifacts at each size tier
# ============================================================
echo "--- Seeding OCI artifacts ---"
for label in "${REQUESTED_SIZES[@]}"; do
  bytes=$(size_to_bytes "$label")
  if [[ -z "$bytes" ]]; then
    echo "Unknown size: $label"; exit 1
  fi
  blob_file="/tmp/bench-${label}.bin"
  repo="bench/blob-${label}"

  if manifest_exists "$repo" "v1"; then
    echo "$label: already seeded, skipping"
    continue
  fi

  if [[ -f "$blob_file" ]]; then
    actual=$(stat -f%z "$blob_file" 2>/dev/null || stat -c%s "$blob_file" 2>/dev/null)
    if [[ "$actual" != "$bytes" ]]; then rm -f "$blob_file"; fi
  fi
  if [[ ! -f "$blob_file" ]]; then
    echo "Generating $label blob ($bytes bytes)..."
    dd if=/dev/urandom of="$blob_file" bs=1048576 count=$((bytes / 1048576)) 2>&1
  fi

  echo "Pushing $label..."
  blob_digest="sha256:$(shasum -a 256 "$blob_file" | awk '{print $1}')"
  blob_size=$(stat -f%z "$blob_file" 2>/dev/null || stat -c%s "$blob_file" 2>/dev/null)
  push_oci_artifact "$repo" "v1" "$blob_file" "$blob_digest" "$blob_size"
  echo "$label: seeded"
done

# ============================================================
# 2. Multi-tag artifact (1mb blob tagged v1..v{TAG_COUNT})
# ============================================================
echo ""
echo "--- Seeding multi-tag artifact (${TAG_COUNT} tags) ---"
MULTI_TAG_REPO="bench/multi-tag"

existing_tag_count=$(curl -s $CURL_AUTH "${BASE_URL}/v2/${MULTI_TAG_REPO}/tags/list" 2>/dev/null \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('tags') or []))" 2>/dev/null || echo "0")

if [[ "$existing_tag_count" -ge "$TAG_COUNT" ]]; then
  echo "multi-tag: already has ${existing_tag_count} tags, skipping"
else
  blob_file="/tmp/bench-1mb.bin"
  blob_digest="sha256:$(shasum -a 256 "$blob_file" | awk '{print $1}')"
  blob_size=$(stat -f%z "$blob_file" 2>/dev/null || stat -c%s "$blob_file" 2>/dev/null)

  push_blob "$MULTI_TAG_REPO" "$blob_file" "$blob_digest" "$blob_size"
  push_blob "$MULTI_TAG_REPO" "$config_file" "$config_digest" "$config_size"

  manifest="{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"${config_digest}\",\"size\":${config_size}},\"layers\":[{\"mediaType\":\"application/octet-stream\",\"digest\":\"${blob_digest}\",\"size\":${blob_size}}]}"

  echo "Tagging ${TAG_COUNT} versions..."
  for i in $(seq 1 "$TAG_COUNT"); do
    curl -s $CURL_AUTH -X PUT \
      -H "Content-Type: application/vnd.oci.image.manifest.v1+json" \
      -d "$manifest" \
      "${BASE_URL}/v2/${MULTI_TAG_REPO}/manifests/v${i}" > /dev/null
  done
  echo "multi-tag: seeded with ${TAG_COUNT} tags"
fi

# ============================================================
# 3. Parallel-pull packages (5 × unique 50MB layers)
# ============================================================
echo ""
echo "--- Seeding parallel-pull packages ---"

if manifest_exists "bench/parallel-unique-1" "v1"; then
  echo "parallel packages: already seeded, skipping"
else
  for n in $(seq 1 5); do
    repo="bench/parallel-unique-${n}"
    unique_file="/tmp/bench-parallel-unique-${n}.bin"
    dd if=/dev/urandom of="$unique_file" bs=1048576 count=50 2>/dev/null
    unique_digest="sha256:$(shasum -a 256 "$unique_file" | awk '{print $1}')"
    unique_size=$(stat -f%z "$unique_file" 2>/dev/null || stat -c%s "$unique_file" 2>/dev/null)
    push_oci_artifact "$repo" "v1" "$unique_file" "$unique_digest" "$unique_size"
    echo "  parallel-unique-${n}: seeded (unique 50MB)"
  done
fi

echo ""
echo "Seeding complete."
