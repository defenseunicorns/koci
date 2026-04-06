package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/opencontainers/go-digest"
	ocispec "github.com/opencontainers/image-spec/specs-go/v1"
	"oras.land/oras-go/v2"
	"oras.land/oras-go/v2/content/oci"
	"oras.land/oras-go/v2/registry/remote"
	"oras.land/oras-go/v2/registry/remote/auth"
)

type BenchResult struct {
	Operation  string  `json:"operation"`
	SizeLabel  string  `json:"sizeLabel"`
	SizeBytes  int64   `json:"sizeBytes"`
	Iteration  int     `json:"iteration"`
	DurationMs float64 `json:"durationMs"`
	Warmup     bool    `json:"warmup"`
	ColdStart  bool    `json:"coldStart"`
}

type repoInfo struct {
	name      string
	tags      []string
	sizeBytes int64
}

// Global auth state for raw HTTP calls (ping, catalog, compound)
var httpUsername, httpPassword string

func authedGet(url string) (*http.Response, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	if httpUsername != "" {
		req.SetBasicAuth(httpUsername, httpPassword)
	}
	return http.DefaultClient.Do(req)
}

var sizeMap = map[string]int64{
	"1mb":   1_048_576,
	"50mb":  52_428_800,
	"500mb": 524_288_000,
}

func main() {
	registryFlag := flag.String("registry", "localhost:5005", "Registry host:port")
	iterationsFlag := flag.Int("iterations", 10, "Number of measured iterations")
	warmupFlag := flag.Int("warmup", 3, "Number of warm-up iterations")
	sizesFlag := flag.String("sizes", "1mb,50mb,500mb,2gb", "Comma-separated artifact sizes")
	usernameFlag := flag.String("username", "", "Registry username")
	passwordFlag := flag.String("password", "", "Registry password")
	discoverFlag := flag.Bool("discover", false, "Auto-discover repos from registry")
	testsFlag := flag.String("tests", "", "Comma-separated tests to run (default: all)")
	flag.Parse()

	registry := *registryFlag
	iterations := *iterationsFlag
	warmup := *warmupFlag
	sizeLabels := strings.Split(*sizesFlag, ",")
	username := *usernameFlag
	password := *passwordFlag
	discover := *discoverFlag

	// Test filter
	enabledTests := map[string]bool{}
	allTests := *testsFlag == ""
	if !allTests {
		for _, t := range strings.Split(*testsFlag, ",") {
			enabledTests[strings.TrimSpace(t)] = true
		}
	}
	enabled := func(name string) bool { return allTests || enabledTests[name] }

	// Set up auth if credentials provided
	httpUsername = username
	httpPassword = password
	if username != "" && password != "" {
		repoCredFunc = func(repo *remote.Repository) {
			repo.Client = &auth.Client{
				Credential: func(ctx context.Context, hostport string) (auth.Credential, error) {
					return auth.Credential{Username: username, Password: password}, nil
				},
			}
		}
	}

	// Detect scheme: explicit http:// = plain HTTP, anything else = HTTPS
	plainHTTP := false
	if strings.HasPrefix(registry, "http://") {
		plainHTTP = true
		registry = strings.TrimPrefix(registry, "http://")
	} else if strings.HasPrefix(registry, "https://") {
		registry = strings.TrimPrefix(registry, "https://")
	}

	ctx := context.Background()
	var results []BenchResult

	log("Cold start ping...")
	start := time.Now()
	if err := ping(registry, plainHTTP); err != nil {
		fmt.Fprintf(os.Stderr, "cold start ping failed: %v\n", err)
		os.Exit(1)
	}
	coldMs := float64(time.Since(start).Microseconds()) / 1000.0
	results = append(results, BenchResult{
		Operation: "ping", SizeLabel: "cold",
		Iteration: 0, DurationMs: coldMs, ColdStart: true,
	})

	if enabled("ping") {
		log("Ping (%d warmup + %d measured)...", warmup, iterations)
		for i := 0; i < warmup; i++ {
			_ = ping(registry, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			_ = ping(registry, plainHTTP)
			results = append(results, BenchResult{
				Operation: "ping", SizeLabel: "n/a",
				Iteration: i, DurationMs: msince(s),
			})
		}
	}

	if enabled("catalog") {
		log("Catalog...")
		for i := 0; i < warmup; i++ {
			_ = catalog(registry, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			err := catalog(registry, plainHTTP)
			if err != nil {
				fmt.Fprintf(os.Stderr, "catalog failed: %v\n", err)
			}
			results = append(results, BenchResult{
				Operation: "catalog", SizeLabel: "n/a",
				Iteration: i, DurationMs: msince(s),
			})
		}
	}

	// ── Discover or use seeded repos ──
	var resolveRepo, resolveTagName string
	type pullEntry struct{ name, tag, label string; sizeBytes int64 }
	var pullEntries []pullEntry
	var multiTagRepo *repoInfo
	var parallelEntries []struct{ name, tag string }
	doSeededPush := false

	if discover {
		log("Discovering repos from registry...")
		allRepos, err := discoverRepos(ctx, registry, plainHTTP)
		if err != nil {
			fmt.Fprintf(os.Stderr, "discover failed: %v\n", err)
			os.Exit(1)
		}
		log("Found %d repos with tags", len(allRepos))
		if len(allRepos) == 0 {
			log("No repos found, skipping remaining tests")
			goto done
		}

		// Compute package sizes
		log("Computing package sizes...")
		for i, r := range allRepos {
			tag := r.tags[len(r.tags)-1]
			repo, err := newRepo(registry, r.name, plainHTTP)
			if err != nil { continue }
			desc, err := repo.Resolve(ctx, tag)
			if err != nil { continue }
			reader, err := repo.Fetch(ctx, desc)
			if err != nil { continue }
			body, _ := io.ReadAll(reader)
			reader.Close()

			// Try as index first (multi-arch), fall back to manifest
			var manifestBytes []byte
			var index ocispec.Index
			if err := json.Unmarshal(body, &index); err == nil && len(index.Manifests) > 0 {
				// It's an index — fetch the first platform manifest
				mReader, err := repo.Fetch(ctx, index.Manifests[0])
				if err == nil {
					manifestBytes, _ = io.ReadAll(mReader)
					mReader.Close()
				}
			} else {
				manifestBytes = body
			}

			if manifestBytes != nil {
				var manifest ocispec.Manifest
				if err := json.Unmarshal(manifestBytes, &manifest); err == nil {
					var total int64
					for _, l := range manifest.Layers { total += l.Size }
					allRepos[i].sizeBytes = total
					log("  %s:%s = %dMB", r.name, tag, total/1024/1024)
				}
			}
		}

		// Sort by size
		sort.Slice(allRepos, func(i, j int) bool { return allRepos[i].sizeBytes < allRepos[j].sizeBytes })

		resolveRepo = allRepos[0].name
		resolveTagName = allRepos[0].tags[len(allRepos[0].tags)-1]

		// Most-tagged repo for multi-tag test
		best := allRepos[0]
		for _, r := range allRepos[1:] {
			if len(r.tags) > len(best.tags) {
				best = r
			}
		}
		if len(best.tags) >= 2 {
			multiTagRepo = &best
		}

		// Pick small, medium, large for pull
		maxPullSize := int64(100 * 1024 * 1024)
		withSize := make([]repoInfo, 0)
		for _, r := range allRepos { if r.sizeBytes > 0 && r.sizeBytes <= maxPullSize { withSize = append(withSize, r) } }
		log("%d repos under 100MB for pull tests", len(withSize))
		if len(withSize) >= 3 {
			small := withSize[0]
			mid := withSize[len(withSize)/2]
			large := withSize[len(withSize)-1]
			picks := []struct{ tier string; r repoInfo }{{"small", small}, {"medium", mid}, {"large", large}}
			for _, p := range picks {
				log("  Pull %s: %s (%dMB)", p.tier, p.r.name, p.r.sizeBytes/1024/1024)
				pullEntries = append(pullEntries, pullEntry{
					p.r.name, p.r.tags[len(p.r.tags)-1],
					fmt.Sprintf("%dMB (%s)", p.r.sizeBytes/1024/1024, p.tier),
					p.r.sizeBytes,
				})
			}
		} else {
			for _, r := range withSize {
				pullEntries = append(pullEntries, pullEntry{
					r.name, r.tags[len(r.tags)-1],
					fmt.Sprintf("%dMB", r.sizeBytes/1024/1024),
					r.sizeBytes,
				})
			}
		}

		// First 5 under 100MB for parallel
		for i, r := range withSize {
			if i >= 5 { break }
			parallelEntries = append(parallelEntries, struct{ name, tag string }{r.name, r.tags[len(r.tags)-1]})
		}
	} else {
		resolveRepo = "bench/blob-" + sizeLabels[0]
		resolveTagName = "v1"
		doSeededPush = true
		multiTagRepo = &repoInfo{"bench/multi-tag", nil, 0} // tags fetched dynamically

		for _, label := range sizeLabels {
			pullEntries = append(pullEntries, pullEntry{"bench/blob-" + label, "v1", label, sizeMap[label]})
		}
		for n := 1; n <= 5; n++ {
			parallelEntries = append(parallelEntries, struct{ name, tag string }{fmt.Sprintf("bench/parallel-unique-%d", n), "v1"})
		}
	}

	if enabled("resolve") {
		log("Resolve (%s:%s)...", resolveRepo, resolveTagName)
		for i := 0; i < warmup; i++ {
			_, _ = resolveTag(ctx, registry, resolveRepo, resolveTagName, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			_, err := resolveTag(ctx, registry, resolveRepo, resolveTagName, plainHTTP)
			if err != nil {
				fmt.Fprintf(os.Stderr, "resolve failed: %v\n", err)
			}
			results = append(results, BenchResult{
				Operation: "resolve", SizeLabel: "n/a",
				Iteration: i, DurationMs: msince(s),
			})
		}
	}

	if enabled("tags") {
		log("Tags (%s)...", resolveRepo)
		for i := 0; i < warmup; i++ {
			_ = listTags(ctx, registry, resolveRepo, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			err := listTags(ctx, registry, resolveRepo, plainHTTP)
			if err != nil {
				fmt.Fprintf(os.Stderr, "tags failed: %v\n", err)
			}
			results = append(results, BenchResult{
				Operation: "tags", SizeLabel: "n/a",
				Iteration: i, DurationMs: msince(s),
			})
		}
	}

	// ── Pull ──
	if enabled("pull") {
	for _, entry := range pullEntries {
		log("Pull %s (%s:%s)...", entry.label, entry.name, entry.tag)
		for i := 0; i < warmup; i++ {
			log("  warmup %d/%d...", i+1, warmup)
			_ = pullToTemp(ctx, registry, entry.name, entry.tag, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			log("  iter %d/%d...", i+1, iterations)
			s := time.Now()
			err := pullToTemp(ctx, registry, entry.name, entry.tag, plainHTTP)
			ms := msince(s)
			if err != nil {
				fmt.Fprintf(os.Stderr, "pull %s iter %d failed: %v\n", entry.label, i, err)
			}
			log("  iter %d/%d done: %.0fms", i+1, iterations, ms)
			results = append(results, BenchResult{
				Operation: "pull", SizeLabel: entry.label, SizeBytes: entry.sizeBytes,
				Iteration: i, DurationMs: ms,
			})
		}
	}
	}

	// ── Push (seeded mode only) ──
	if enabled("push") && doSeededPush {
		for _, label := range sizeLabels {
			bytes := sizeMap[label]
			blobPath := fmt.Sprintf("/tmp/bench-push-go-%s.bin", label)
			if _, err := os.Stat(blobPath); err != nil {
				fmt.Fprintf(os.Stderr, "skipping push for %s: %s not found\n", label, blobPath)
				continue
			}
			log("Push %s...", label)
			dgst, fsize, err := computeFileDigest(blobPath)
			if err != nil {
				fmt.Fprintf(os.Stderr, "digest %s failed: %v\n", label, err)
				continue
			}
			// Single-shot push to a fresh repo
			pushRepoName := fmt.Sprintf("bench/push-oras-%s", label)
			s := time.Now()
			err = pushFromFile(ctx, registry, pushRepoName, "v1", blobPath, plainHTTP, dgst, fsize)
			ms := msince(s)
			if err != nil {
				fmt.Fprintf(os.Stderr, "push %s failed: %v\n", label, err)
			}
			log("  done: %.0fms", ms)
			results = append(results, BenchResult{
				Operation: "push", SizeLabel: label, SizeBytes: bytes,
				Iteration: 0, DurationMs: ms,
			})
		}
	} else {
		log("Push: skipped (discover mode)")
	}

	// ── Multi-tag ──
	if (enabled("multitag-list") || enabled("multitag-process")) && multiTagRepo != nil {
		mtName := multiTagRepo.name
		var mtTags []string
		_ = listTagsInto(ctx, registry, mtName, plainHTTP, &mtTags)
		tagCount := len(mtTags)
		tagLabel := fmt.Sprintf("%d-tags", tagCount)

		if enabled("multitag-list") {
			log("Multi-tag: list %d tags (%s)...", tagCount, mtName)
			for i := 0; i < warmup; i++ {
				_ = listTags(ctx, registry, mtName, plainHTTP)
			}
			for i := 0; i < iterations; i++ {
				s := time.Now()
				_ = listTags(ctx, registry, mtName, plainHTTP)
				results = append(results, BenchResult{
					Operation: "tags-many-list", SizeLabel: tagLabel,
					Iteration: i, DurationMs: msince(s),
				})
			}
		}

		if enabled("multitag-process") {
			log("Multi-tag: list + resolve + manifest per tag (%s, %d tags)...", mtName, tagCount)
			for i := 0; i < warmup; i++ {
				log("  warmup %d/%d...", i+1, warmup)
				_ = processAllTags(ctx, registry, mtName, plainHTTP)
			}
			for i := 0; i < iterations; i++ {
				log("  iter %d/%d (%d tags)...", i+1, iterations, tagCount)
				s := time.Now()
				_ = processAllTags(ctx, registry, mtName, plainHTTP)
				ms := msince(s)
				log("  iter %d/%d done: %.0fms", i+1, iterations, ms)
				results = append(results, BenchResult{
					Operation: "tags-many-process", SizeLabel: tagLabel,
					Iteration: i, DurationMs: ms,
				})
			}
		}
	}

	// ── Parallel pull ──
	if (enabled("parallel-seq") || enabled("parallel-conc")) && len(parallelEntries) >= 2 {
		count := len(parallelEntries)
		countLabel := fmt.Sprintf("%d-packages", count)

		if enabled("parallel-seq") {
			log("Parallel pull: sequential (%d packages)...", count)
			for i := 0; i < warmup; i++ {
				for _, e := range parallelEntries {
					_ = pullToTemp(ctx, registry, e.name, e.tag, plainHTTP)
				}
			}
			for i := 0; i < iterations; i++ {
				s := time.Now()
				for _, e := range parallelEntries {
					_ = pullToTemp(ctx, registry, e.name, e.tag, plainHTTP)
				}
				results = append(results, BenchResult{
					Operation: "parallel-seq", SizeLabel: countLabel,
					Iteration: i, DurationMs: msince(s),
				})
			}
		}

		if enabled("parallel-conc") {
			log("Parallel pull: concurrent (%d packages)...", count)
			for i := 0; i < warmup; i++ {
				parallelPullEntries(ctx, registry, parallelEntries, plainHTTP)
			}
			for i := 0; i < iterations; i++ {
				s := time.Now()
				parallelPullEntries(ctx, registry, parallelEntries, plainHTTP)
				results = append(results, BenchResult{
					Operation: "parallel-conc", SizeLabel: countLabel,
					Iteration: i, DurationMs: msince(s),
				})
			}
		}
	}

	if enabled("discovery-list") {
		log("Discovery: list() only...")
		for i := 0; i < warmup; i++ {
			_ = compoundList(ctx, registry, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			err := compoundList(ctx, registry, plainHTTP)
			if err != nil {
				fmt.Fprintf(os.Stderr, "compound-list failed: %v\n", err)
			}
			results = append(results, BenchResult{
				Operation: "compound-list", SizeLabel: "all-repos",
				Iteration: i, DurationMs: msince(s),
			})
		}
	}

	if enabled("discovery-full") {
		log("Discovery: list() + process all...")
		for i := 0; i < warmup; i++ {
			_ = compoundFull(ctx, registry, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			err := compoundFull(ctx, registry, plainHTTP)
			if err != nil {
				fmt.Fprintf(os.Stderr, "compound-full failed: %v\n", err)
			}
			results = append(results, BenchResult{
				Operation: "compound-full", SizeLabel: "all-repos",
				Iteration: i, DurationMs: msince(s),
			})
		}
	}

done:
	log("Done. %d results collected.", len(results))

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	if err := enc.Encode(results); err != nil {
		fmt.Fprintf(os.Stderr, "json encode error: %v\n", err)
		os.Exit(1)
	}
}

func msince(t time.Time) float64 {
	return float64(time.Since(t).Microseconds()) / 1000.0
}

func log(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "  [oras-go] "+format+"\n", args...)
}

func ping(registry string, plainHTTP bool) error {
	scheme := "https"
	if plainHTTP {
		scheme = "http"
	}
	resp, err := authedGet(fmt.Sprintf("%s://%s/v2/", scheme, registry))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.ReadAll(resp.Body)
	return nil
}

func catalog(registry string, plainHTTP bool) error {
	scheme := "https"
	if plainHTTP {
		scheme = "http"
	}
	resp, err := authedGet(fmt.Sprintf("%s://%s/v2/_catalog", scheme, registry))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.ReadAll(resp.Body)
	return nil
}

var repoCredFunc func(*remote.Repository)

func newRepo(registry, name string, plainHTTP bool) (*remote.Repository, error) {
	repo, err := remote.NewRepository(registry + "/" + name)
	if err != nil {
		return nil, err
	}
	repo.PlainHTTP = plainHTTP
	if repoCredFunc != nil {
		repoCredFunc(repo)
	}
	return repo, nil
}

func resolveTag(ctx context.Context, registry, name, tag string, plainHTTP bool) (ocispec.Descriptor, error) {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return ocispec.Descriptor{}, err
	}
	return repo.Resolve(ctx, tag)
}

func pullToTemp(ctx context.Context, registry, name, tag string, plainHTTP bool) error {
	tmpDir, err := os.MkdirTemp("", "oras-bench-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmpDir)

	store, err := oci.New(tmpDir)
	if err != nil {
		return err
	}

	src, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return err
	}

	_, err = oras.Copy(ctx, src, tag, store, tag, oras.CopyOptions{})
	return err
}

func computeFileDigest(path string) (digest.Digest, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()

	h := sha256.New()
	size, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	dgst := digest.NewDigestFromEncoded(digest.SHA256, hex.EncodeToString(h.Sum(nil)))
	return dgst, size, nil
}

func pushFromFile(ctx context.Context, registry, name, tag, blobPath string, plainHTTP bool, dgst digest.Digest, size int64) error {
	tmpDir, err := os.MkdirTemp("", "oras-bench-push-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmpDir)

	store, err := oci.New(tmpDir)
	if err != nil {
		return err
	}

	blobDesc := ocispec.Descriptor{
		MediaType: "application/octet-stream",
		Digest:    dgst,
		Size:      size,
	}

	f, err := os.Open(blobPath)
	if err != nil {
		return err
	}
	err = store.Push(ctx, blobDesc, f)
	f.Close()
	if err != nil {
		return fmt.Errorf("push blob to store: %w", err)
	}

	manifestDesc, err := oras.PackManifest(ctx, store, oras.PackManifestVersion1_1, "application/octet-stream", oras.PackManifestOptions{
		Layers: []ocispec.Descriptor{blobDesc},
	})
	if err != nil {
		return fmt.Errorf("pack manifest: %w", err)
	}

	if err = store.Tag(ctx, manifestDesc, tag); err != nil {
		return fmt.Errorf("tag: %w", err)
	}

	dst, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return err
	}

	_, err = oras.Copy(ctx, store, tag, dst, tag, oras.CopyOptions{})
	return err
}

// processAllTags lists tags then does resolve+manifest for each — the real multi-tag bottleneck.
func processAllTags(ctx context.Context, registry, name string, plainHTTP bool) error {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return err
	}
	var tags []string
	err = repo.Tags(ctx, "", func(t []string) error {
		tags = append(tags, t...)
		return nil
	})
	if err != nil {
		return err
	}
	for _, tag := range tags {
		desc, err := repo.Resolve(ctx, tag)
		if err != nil {
			continue
		}
		reader, err := repo.Fetch(ctx, desc)
		if err != nil {
			continue
		}
		_, _ = io.ReadAll(reader)
		reader.Close()
	}
	return nil
}

func discoverRepos(ctx context.Context, registry string, plainHTTP bool) ([]repoInfo, error) {
	scheme := "https"
	if plainHTTP { scheme = "http" }
	resp, err := authedGet(fmt.Sprintf("%s://%s/v2/_catalog", scheme, registry))
	if err != nil { return nil, err }
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var cat struct{ Repositories []string `json:"repositories"` }
	if err := json.Unmarshal(body, &cat); err != nil { return nil, err }

	var result []repoInfo
	for _, name := range cat.Repositories {
		var tags []string
		_ = listTagsInto(ctx, registry, name, plainHTTP, &tags)
		if len(tags) > 0 {
			result = append(result, repoInfo{name, tags, 0})
		}
	}
	return result, nil
}

func listTagsInto(ctx context.Context, registry, name string, plainHTTP bool, out *[]string) error {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil { return err }
	return repo.Tags(ctx, "", func(tags []string) error {
		*out = append(*out, tags...)
		return nil
	})
}

func parallelPullEntries(ctx context.Context, registry string, entries []struct{ name, tag string }, plainHTTP bool) {
	var wg sync.WaitGroup
	for _, e := range entries {
		wg.Add(1)
		go func(name, tag string) {
			defer wg.Done()
			_ = pullToTemp(ctx, registry, name, tag, plainHTTP)
		}(e.name, e.tag)
	}
	wg.Wait()
}

func parallelPull(ctx context.Context, registry string, repos []string, tag string, plainHTTP bool) {
	var wg sync.WaitGroup
	for _, r := range repos {
		wg.Add(1)
		go func(repoName string) {
			defer wg.Done()
			_ = pullToTemp(ctx, registry, repoName, tag, plainHTTP)
		}(r)
	}
	wg.Wait()
}

// compoundList simulates koci's registry.extensions.list(): catalog then tags per repo sequentially.
func compoundList(ctx context.Context, registry string, plainHTTP bool) error {
	scheme := "https"
	if plainHTTP {
		scheme = "http"
	}

	// 1. Catalog
	resp, err := authedGet(fmt.Sprintf("%s://%s/v2/_catalog", scheme, registry))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var catalogResp struct {
		Repositories []string `json:"repositories"`
	}
	if err := json.Unmarshal(body, &catalogResp); err != nil {
		return err
	}

	// 2. Sequential tags per repo (mirrors flatMapConcat behavior)
	for _, repoName := range catalogResp.Repositories {
		_ = listTags(ctx, registry, repoName, plainHTTP)
	}
	return nil
}

// compoundFull simulates the full UDS Android retrieveMetadata flow:
// catalog → sequential tags → for each repo+tag: resolve index → manifest → fetch zarf.yaml
// compoundFull: catalog → sequential tags → resolve + fetch manifest per repo.
func compoundFull(ctx context.Context, registry string, plainHTTP bool) error {
	scheme := "https"
	if plainHTTP {
		scheme = "http"
	}

	resp, err := authedGet(fmt.Sprintf("%s://%s/v2/_catalog", scheme, registry))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)

	var catalogResp struct {
		Repositories []string `json:"repositories"`
	}
	if err := json.Unmarshal(body, &catalogResp); err != nil {
		return err
	}

	for _, repoName := range catalogResp.Repositories {
		repo, err := newRepo(registry, repoName, plainHTTP)
		if err != nil {
			continue
		}
		var tags []string
		_ = repo.Tags(ctx, "", func(t []string) error {
			tags = append(tags, t...)
			return nil
		})
		if len(tags) == 0 {
			continue
		}

		tag := tags[len(tags)-1]
		desc, err := repo.Resolve(ctx, tag)
		if err != nil {
			continue
		}

		reader, err := repo.Fetch(ctx, desc)
		if err != nil {
			continue
		}
		_, _ = io.ReadAll(reader)
		reader.Close()
	}
	return nil
}

func listTags(ctx context.Context, registry, name string, plainHTTP bool) error {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return err
	}
	return repo.Tags(ctx, "", func(tags []string) error {
		return nil
	})
}
