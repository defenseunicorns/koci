package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"

	godigest "github.com/opencontainers/go-digest"
	ocispec "github.com/opencontainers/image-spec/specs-go/v1"
	oras "oras.land/oras-go/v2"
	"oras.land/oras-go/v2/content/oci"
	"oras.land/oras-go/v2/registry/remote"
	"oras.land/oras-go/v2/registry/remote/auth"
)

type BenchResult struct {
	Operation  string  `json:"operation"`
	SizeLabel  string  `json:"sizeLabel"`
	Iteration  int     `json:"iteration"`
	DurationMs float64 `json:"durationMs"`
	SizeBytes  int64   `json:"sizeBytes,omitempty"`
}

// Global auth state for raw HTTP calls (ping, catalog).
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

func main() {
	registryFlag := flag.String("registry", "localhost:5005", "Registry host:port")
	iterationsFlag := flag.Int("iterations", 10, "Number of measured iterations")
	warmupFlag := flag.Int("warmup", 3, "Number of warm-up iterations")
	usernameFlag := flag.String("username", "", "Registry username")
	passwordFlag := flag.String("password", "", "Registry password")
	testsFlag := flag.String("tests", "", "Comma-separated tests to run (default: all)")
	flag.Parse()

	registry := *registryFlag
	iterations := *iterationsFlag
	warmup := *warmupFlag
	username := *usernameFlag
	password := *passwordFlag

	enabledTests := map[string]bool{}
	allTests := *testsFlag == ""
	if !allTests {
		for _, t := range strings.Split(*testsFlag, ",") {
			enabledTests[strings.TrimSpace(t)] = true
		}
	}
	enabled := func(name string) bool {
		if name == "push" {
			return enabledTests[name]
		}
		return allTests || enabledTests[name]
	}

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

	plainHTTP := false
	if strings.HasPrefix(registry, "http://") {
		plainHTTP = true
		registry = strings.TrimPrefix(registry, "http://")
	} else if strings.HasPrefix(registry, "https://") {
		registry = strings.TrimPrefix(registry, "https://")
	}

	ctx := context.Background()
	var results []BenchResult

	// ── Ping ──
	if enabled("ping") {
		log("Ping (%d warmup + %d measured)...", warmup, iterations)
		for i := 0; i < warmup; i++ {
			_ = ping(registry, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			_ = ping(registry, plainHTTP)
			results = append(results, BenchResult{Operation: "ping", SizeLabel: "n/a", Iteration: i, DurationMs: msince(s)})
		}
	}

	// ── Catalog ──
	if enabled("catalog") {
		log("Catalog...")
		for i := 0; i < warmup; i++ {
			_ = catalogFetch(registry, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			_ = catalogFetch(registry, plainHTTP)
			results = append(results, BenchResult{Operation: "catalog", SizeLabel: "n/a", Iteration: i, DurationMs: msince(s)})
		}
	}

	// ── Discover all repos ──
	log("Discovering repos...")
	type target struct {
		name, tag, label string
		sizeBytes        int64
	}
	var discovered []target

	repos, _ := catalogRepos(registry, plainHTTP)
	for _, name := range repos {
		tags, err := listTagsSlice(ctx, registry, name, plainHTTP)
		if err != nil || len(tags) == 0 {
			continue
		}
		discovered = append(discovered, target{name: name, tag: tags[len(tags)-1]})
	}

	if len(discovered) == 0 {
		log("No repos found, skipping remaining tests")
		output(results)
		return
	}

	log("Computing sizes...")
	for i := range discovered {
		discovered[i].sizeBytes = sizeOf(ctx, registry, discovered[i].name, discovered[i].tag, plainHTTP)
		discovered[i].label = discovered[i].name
		log("  %s:%s = %dMB", discovered[i].name, discovered[i].tag, discovered[i].sizeBytes/1024/1024)
	}

	resolveRepo := discovered[0].name
	resolveTag := discovered[0].tag

	// ── Resolve ──
	if enabled("resolve") {
		log("Resolve (%s:%s)...", resolveRepo, resolveTag)
		for i := 0; i < warmup; i++ {
			_ = resolve(ctx, registry, resolveRepo, resolveTag, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			_ = resolve(ctx, registry, resolveRepo, resolveTag, plainHTTP)
			results = append(results, BenchResult{Operation: "resolve", SizeLabel: "n/a", Iteration: i, DurationMs: msince(s)})
		}
	}

	// ── Tags ──
	if enabled("tags") {
		log("Tags (%s)...", resolveRepo)
		for i := 0; i < warmup; i++ {
			_ = listTags(ctx, registry, resolveRepo, plainHTTP)
		}
		for i := 0; i < iterations; i++ {
			s := time.Now()
			_ = listTags(ctx, registry, resolveRepo, plainHTTP)
			results = append(results, BenchResult{Operation: "tags", SizeLabel: "n/a", Iteration: i, DurationMs: msince(s)})
		}
	}

	// ── Pull ──
	if enabled("pull") {
		for _, t := range discovered {
			log("Pull %s:%s...", t.name, t.tag)
			for i := 0; i < warmup; i++ {
				log("  warmup %d/%d...", i+1, warmup)
				_ = pullToTemp(ctx, registry, t.name, t.tag, plainHTTP)
			}
			for i := 0; i < iterations; i++ {
				log("  iter %d/%d...", i+1, iterations)
				http.DefaultTransport.(*http.Transport).CloseIdleConnections()
				s := time.Now()
				err := pullToTemp(ctx, registry, t.name, t.tag, plainHTTP)
				ms := msince(s)
				if err != nil {
					fmt.Fprintf(os.Stderr, "pull %s iter %d failed: %v\n", t.label, i, err)
				}
				log("  iter %d/%d done: %.0fms", i+1, iterations, ms)
				results = append(results, BenchResult{Operation: "pull", SizeLabel: t.label, Iteration: i, DurationMs: ms, SizeBytes: t.sizeBytes})
			}
		}
	}

	// ── Push ──
	if enabled("push") {
		type pushSize struct {
			label string
			bytes int64
		}
		pushSizes := []pushSize{
			{"5mb", 5 * 1024 * 1024},
			{"50mb", 50 * 1024 * 1024},
			{"500mb", 500 * 1024 * 1024},
			{"1000mb", 1000 * 1024 * 1024},
		}
		for _, ps := range pushSizes {
			log("Push %s (%d iterations)...", ps.label, iterations)
			content := make([]byte, ps.bytes)
			if _, err := rand.Read(content); err != nil {
				fmt.Fprintf(os.Stderr, "push %s rand error: %v\n", ps.label, err)
				continue
			}
			for i := 0; i < iterations; i++ {
				log("  iter %d/%d...", i+1, iterations)
				binary.LittleEndian.PutUint64(content, uint64(i))
				h := sha256.Sum256(content)
				desc := ocispec.Descriptor{
					MediaType: "application/octet-stream",
					Digest:    godigest.Digest("sha256:" + hex.EncodeToString(h[:])),
					Size:      ps.bytes,
				}
				repoName := fmt.Sprintf("push-%s-%d", ps.label, time.Now().UnixNano())
				repo, err := newRepo(registry, repoName, plainHTTP)
				if err != nil {
					fmt.Fprintf(os.Stderr, "push %s repo error: %v\n", ps.label, err)
					break
				}
				s := time.Now()
				err = repo.Push(ctx, desc, bytes.NewReader(content))
				ms := msince(s)
				if err != nil {
					fmt.Fprintf(os.Stderr, "push %s iter %d failed: %v\n", ps.label, i, err)
				}
				results = append(results, BenchResult{Operation: "push", SizeLabel: ps.label, Iteration: i, DurationMs: ms, SizeBytes: ps.bytes})
			}
		}
	}

	output(results)
}

func output(results []BenchResult) {
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

func catalogFetch(registry string, plainHTTP bool) error {
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

func catalogRepos(registry string, plainHTTP bool) ([]string, error) {
	scheme := "https"
	if plainHTTP {
		scheme = "http"
	}
	resp, err := authedGet(fmt.Sprintf("%s://%s/v2/_catalog", scheme, registry))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	var cat struct {
		Repositories []string `json:"repositories"`
	}
	if err := json.Unmarshal(body, &cat); err != nil {
		return nil, err
	}
	return cat.Repositories, nil
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

func resolve(ctx context.Context, registry, name, tag string, plainHTTP bool) error {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return err
	}
	_, err = repo.Resolve(ctx, tag)
	return err
}

func listTags(ctx context.Context, registry, name string, plainHTTP bool) error {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return err
	}
	return repo.Tags(ctx, "", func(tags []string) error { return nil })
}

func listTagsSlice(ctx context.Context, registry, name string, plainHTTP bool) ([]string, error) {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return nil, err
	}
	var tags []string
	err = repo.Tags(ctx, "", func(t []string) error {
		tags = append(tags, t...)
		return nil
	})
	return tags, err
}

func sizeOf(ctx context.Context, registry, name, tag string, plainHTTP bool) int64 {
	repo, err := newRepo(registry, name, plainHTTP)
	if err != nil {
		return 0
	}
	desc, err := repo.Resolve(ctx, tag)
	if err != nil {
		return 0
	}
	reader, err := repo.Fetch(ctx, desc)
	if err != nil {
		return 0
	}
	body, _ := io.ReadAll(reader)
	reader.Close()

	// Try as index first (multi-arch), fall back to direct manifest.
	var index ocispec.Index
	if json.Unmarshal(body, &index) == nil && len(index.Manifests) > 0 {
		mReader, err := repo.Fetch(ctx, index.Manifests[0])
		if err != nil {
			return 0
		}
		body, _ = io.ReadAll(mReader)
		mReader.Close()
	}
	var manifest ocispec.Manifest
	if json.Unmarshal(body, &manifest) != nil {
		return 0
	}
	var total int64
	for _, l := range manifest.Layers {
		total += l.Size
	}
	return total
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