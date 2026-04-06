import com.defenseunicorns.koci.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.java.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BenchResult(
  val operation: String,
  val sizeLabel: String,
  val sizeBytes: Long,
  val iteration: Int,
  val durationMs: Double,
  val warmup: Boolean,
  val coldStart: Boolean = false,
)

val SIZE_MAP =
  mapOf(
    "1mb" to 1_048_576L,
    "50mb" to 52_428_800L,
    "500mb" to 524_288_000L,
  )

fun parseArgs(args: Array<String>): Map<String, String> {
  val map = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--registry", "-r" -> { map["registry"] = args[++i]; i++ }
      "--iterations", "-n" -> { map["iterations"] = args[++i]; i++ }
      "--warmup", "-w" -> { map["warmup"] = args[++i]; i++ }
      "--sizes", "-s" -> { map["sizes"] = args[++i]; i++ }
      "--username", "-u" -> { map["username"] = args[++i]; i++ }
      "--password", "-p" -> { map["password"] = args[++i]; i++ }
      "--discover" -> { map["discover"] = "true"; i++ }
      "--output", "-o" -> { map["output"] = args[++i]; i++ }
      "--tests", "-t" -> { map["tests"] = args[++i]; i++ }
      else -> i++
    }
  }
  return map
}

inline fun timeNanoMs(block: () -> Unit): Double {
  val ns = System.nanoTime()
  block()
  return (System.nanoTime() - ns) / 1_000_000.0
}

fun log(msg: String) = System.err.println("  [koci] $msg")

data class RepoInfo(val name: String, val tags: List<String>, val sizeBytes: Long = 0)

fun main(args: Array<String>) = runBlocking {
  val opts = parseArgs(args)
  val registryUrl = opts["registry"] ?: "http://127.0.0.1:5005"
  val iterations = opts["iterations"]?.toInt() ?: 10
  val warmupRuns = opts["warmup"]?.toInt() ?: 3
  val sizeLabels = (opts["sizes"] ?: "1mb,50mb,500mb,2gb").split(",")
  val discover = opts["discover"] == "true"
  val username = opts["username"]
  val password = opts["password"]
  val enabledTests = opts["tests"]?.split(",")?.toSet()
  fun enabled(test: String) = enabledTests == null || test in enabledTests

  val results = mutableListOf<BenchResult>()
  // Use Java engine for HTTPS (better TLS compat), CIO for HTTP
  val useJavaEngine = registryUrl.startsWith("https://")
  val client =
    if (username != null && password != null) {
      HttpClient(if (useJavaEngine) Java.create() else CIO.create()) {
        install(OCIAuthPlugin) {
          cred = Credential(username = username, password = password, refreshToken = "", accessToken = "")
        }
      }
    } else {
      HttpClient(if (useJavaEngine) Java.create() else CIO.create())
    }
  if (useJavaEngine) log("Using Java HTTP engine (HTTPS)")
  val registry = Registry(registryUrl, client)

  // ── Cold start ──
  log("Cold start ping...")
  val coldMs = timeNanoMs { registry.ping().getOrThrow() }
  results.add(BenchResult("ping", "cold", 0, 0, coldMs, warmup = false, coldStart = true))

  // ── Ping ──
  if (enabled("ping")) {
    log("Ping ($warmupRuns warmup + $iterations measured)...")
    repeat(warmupRuns) { registry.ping().getOrThrow() }
    repeat(iterations) { i ->
      val ms = timeNanoMs { registry.ping().getOrThrow() }
      results.add(BenchResult("ping", "n/a", 0, i, ms, warmup = false))
    }
  }

  // ── Catalog ──
  if (enabled("catalog")) {
    log("Catalog...")
    repeat(warmupRuns) { registry.extensions.catalog().getOrThrow() }
    repeat(iterations) { i ->
      val ms = timeNanoMs { registry.extensions.catalog().getOrThrow() }
      results.add(BenchResult("catalog", "n/a", 0, i, ms, warmup = false))
    }
  }

  // ── Discover or use seeded repos ──
  val allRepos: List<RepoInfo>
  val resolveRepo: String
  val resolveTag: String
  val multiTagRepo: RepoInfo?
  data class PullTarget(val name: String, val tag: String, val label: String, val sizeBytes: Long)
  val pullRepos: List<PullTarget>
  val parallelRepos: List<Pair<String, String>>

  if (discover) {
    log("Discovering repos from registry...")
    val tagsResponses = registry.extensions.list().toList()
    val reposWithTags = tagsResponses
      .filter { !it.tags.isNullOrEmpty() }
      .map { RepoInfo(it.name, it.tags!!) }
    log("Found ${reposWithTags.size} repos with tags")

    if (reposWithTags.isEmpty()) {
      log("No repos found, skipping remaining tests")
      println(Json { prettyPrint = true }.encodeToString(results))
      return@runBlocking
    }

    // Compute sizes by resolving manifests
    log("Computing package sizes...")
    allRepos = reposWithTags.map { repo ->
      val tag = repo.tags.last()
      try {
        val r = registry.repo(repo.name)
        val desc = r.resolve(tag).getOrThrow()
        // Handle both manifests and indices (multi-arch)
        val manifest = when (desc.mediaType) {
          INDEX_MEDIA_TYPE -> {
            val index = r.index(desc).getOrThrow()
            val platformDesc = index.manifests.firstOrNull()
            if (platformDesc != null) r.manifest(platformDesc).getOrThrow() else null
          }
          else -> r.manifest(desc).getOrThrow()
        }
        val totalSize = manifest?.layers?.sumOf { it.size } ?: 0L
        log("  ${repo.name}:$tag = ${totalSize / 1024 / 1024}MB")
        RepoInfo(repo.name, repo.tags, totalSize)
      } catch (e: Exception) {
        log("  ${repo.name}:$tag = unknown size (${e.message})")
        RepoInfo(repo.name, repo.tags, 0)
      }
    }.sortedBy { it.sizeBytes }

    // First repo for resolve/tags
    resolveRepo = allRepos.first().name
    resolveTag = allRepos.first().tags.last()

    // Repo with most tags for multi-tag test
    multiTagRepo = allRepos.maxByOrNull { it.tags.size }

    // Pick small, medium, large for pull tests
    val maxPullSize = 100L * 1024 * 1024
    val withSize = allRepos.filter { it.sizeBytes in 1..maxPullSize }
    log("${withSize.size} repos under 100MB for pull tests")
    pullRepos = if (withSize.size >= 3) {
      val small = withSize.first()
      val large = withSize.last()
      val mid = withSize[withSize.size / 2]
      val picks = listOf("small" to small, "medium" to mid, "large" to large)
      for ((tier, r) in picks) {
        log("  Pull $tier: ${r.name} (${r.sizeBytes / 1024 / 1024}MB)")
      }
      picks.map { (tier, r) -> PullTarget(r.name, r.tags.last(), "${r.sizeBytes / 1024 / 1024}MB ($tier)", r.sizeBytes) }
    } else {
      withSize.map { PullTarget(it.name, it.tags.last(), "${it.sizeBytes / 1024 / 1024}MB", it.sizeBytes) }
    }

    // First 5 under 100MB for parallel
    parallelRepos = withSize.take(5).map { it.name to it.tags.last() }
  } else {
    resolveRepo = "bench/blob-${sizeLabels.first()}"
    resolveTag = "v1"
    multiTagRepo = RepoInfo("bench/multi-tag", (1..100).map { "v$it" })
    pullRepos = sizeLabels.map { PullTarget("bench/blob-$it", "v1", it, SIZE_MAP[it] ?: 0) }
    parallelRepos = (1..5).map { "bench/parallel-unique-$it" to "v1" }
    allRepos = emptyList() // not used in seeded mode
  }

  // ── Resolve ──
  if (enabled("resolve")) {
    log("Resolve ($resolveRepo:$resolveTag)...")
    repeat(warmupRuns) { registry.resolve(resolveRepo, resolveTag) }
    repeat(iterations) { i ->
      val ms = timeNanoMs { registry.resolve(resolveRepo, resolveTag).getOrThrow() }
      results.add(BenchResult("resolve", "n/a", 0, i, ms, warmup = false))
    }
  }

  // ── Tags ──
  if (enabled("tags")) {
    log("Tags ($resolveRepo)...")
    repeat(warmupRuns) { registry.tags(resolveRepo).getOrThrow() }
    repeat(iterations) { i ->
      val ms = timeNanoMs { registry.tags(resolveRepo).getOrThrow() }
      results.add(BenchResult("tags", "n/a", 0, i, ms, warmup = false))
    }
  }

  // ── Pull ──
  if (enabled("pull")) for (target in pullRepos) {
    log("Pull ${target.label} (${target.name}:${target.tag})...")
    repeat(warmupRuns) { w ->
      log("  warmup ${w + 1}/$warmupRuns...")
      val tmp = Files.createTempDirectory("koci-bench")
      val layout = Layout.create(tmp.toString()).getOrThrow()
      registry.pull(target.name, target.tag, layout).last()
      tmp.toFile().deleteRecursively()
    }
    repeat(iterations) { i ->
      log("  iter ${i + 1}/$iterations...")
      val tmp = Files.createTempDirectory("koci-bench")
      val layout = Layout.create(tmp.toString()).getOrThrow()
      val ms = timeNanoMs { registry.pull(target.name, target.tag, layout).last() }
      tmp.toFile().deleteRecursively()
      log("  iter ${i + 1}/$iterations done: ${String.format("%.0f", ms)}ms")
      results.add(BenchResult("pull", target.label, target.sizeBytes, i, ms, warmup = false))
    }
  }

  // ── Push (seeded mode only — needs blob files on disk) ──
  if (enabled("push") && !discover) {
    for (label in sizeLabels) {
      val bytes = SIZE_MAP[label] ?: error("Unknown size: $label")
      val blobFile = File("/tmp/bench-push-kt-$label.bin")
      if (!blobFile.exists()) {
        System.err.println("Skipping push for $label: $blobFile not found")
        continue
      }
      log("Push $label...")
      val desc = Descriptor.fromInputStream(mediaType = "application/octet-stream", stream = blobFile.inputStream())
      val configBytes = "{}".toByteArray()
      val configDesc = Descriptor.fromInputStream(mediaType = MANIFEST_CONFIG_MEDIA_TYPE, stream = configBytes.inputStream())

      // Single-shot push to a fresh repo
      val repo = registry.repo("bench/push-koci-$label")
      val ms = timeNanoMs {
        repo.push(configBytes.inputStream(), configDesc).last()
        repo.push(blobFile.inputStream(), desc).last()
        repo.tag(Manifest(schemaVersion = 2, config = configDesc, layers = listOf(desc)), "v1").getOrThrow()
      }
      log("  done: ${String.format("%.0f", ms)}ms")
      results.add(BenchResult("push", label, bytes, 0, ms, warmup = false))
    }
  } else {
    log("Push: skipped (discover mode, no local blob files)")
  }

  // ── Multi-tag ──
  val mtRepo = multiTagRepo
  if ((enabled("multitag-list") || enabled("multitag-process")) && mtRepo != null && mtRepo.tags.size >= 2) {
    val tagCount = mtRepo.tags.size

    if (enabled("multitag-list")) {
      log("Multi-tag: list ${tagCount} tags (${mtRepo.name})...")
      repeat(warmupRuns) { registry.tags(mtRepo.name).getOrThrow() }
      repeat(iterations) { i ->
        val ms = timeNanoMs { registry.tags(mtRepo.name).getOrThrow() }
        results.add(BenchResult("tags-many-list", "${tagCount}-tags", 0, i, ms, warmup = false))
      }
    }

    if (enabled("multitag-process")) {
      log("Multi-tag: list + resolve + manifest per tag (${mtRepo.name}, $tagCount tags)...")
      val mtRepoObj = registry.repo(mtRepo.name)
      repeat(warmupRuns) {
        val tags = registry.tags(mtRepo.name).getOrThrow().tags ?: emptyList()
        for (tag in tags) {
          try {
            val desc = mtRepoObj.resolve(tag).getOrThrow()
            mtRepoObj.manifest(desc).getOrThrow()
          } catch (_: Exception) {}
        }
      }
      repeat(iterations) { i ->
        log("  iter ${i + 1}/$iterations ($tagCount tags)...")
        val ms = timeNanoMs {
          val tags = registry.tags(mtRepo.name).getOrThrow().tags ?: emptyList()
          for (tag in tags) {
            try {
              val desc = mtRepoObj.resolve(tag).getOrThrow()
              mtRepoObj.manifest(desc).getOrThrow()
            } catch (_: Exception) {}
          }
        }
        log("  iter ${i + 1}/$iterations done: ${String.format("%.0f", ms)}ms")
        results.add(BenchResult("tags-many-process", "${tagCount}-tags", 0, i, ms, warmup = false))
      }
    }
  } else {
    log("Multi-tag: skipped (no repo with 2+ tags found)")
  }

  // ── Parallel pull ──
  if ((enabled("parallel-seq") || enabled("parallel-conc")) && parallelRepos.size >= 2) {
    val count = parallelRepos.size

    if (enabled("parallel-seq")) {
      log("Parallel pull: sequential ($count packages)...")
      repeat(warmupRuns) {
        for ((name, tag) in parallelRepos) {
          val tmp = Files.createTempDirectory("koci-bench-par")
          val layout = Layout.create(tmp.toString()).getOrThrow()
          registry.pull(name, tag, layout).last()
          tmp.toFile().deleteRecursively()
        }
      }
      repeat(iterations) { i ->
        val ms = timeNanoMs {
          for ((name, tag) in parallelRepos) {
            val tmp = Files.createTempDirectory("koci-bench-par")
            val layout = Layout.create(tmp.toString()).getOrThrow()
            registry.pull(name, tag, layout).last()
            tmp.toFile().deleteRecursively()
          }
        }
        results.add(BenchResult("parallel-seq", "${count}-packages", 0, i, ms, warmup = false))
      }
    }

    if (enabled("parallel-conc")) {
      log("Parallel pull: concurrent ($count packages)...")
    repeat(warmupRuns) {
      coroutineScope {
        parallelRepos.map { (name, tag) ->
          async {
            val tmp = Files.createTempDirectory("koci-bench-par")
            val layout = Layout.create(tmp.toString()).getOrThrow()
            registry.pull(name, tag, layout).last()
            tmp.toFile().deleteRecursively()
          }
        }.awaitAll()
      }
    }
    repeat(iterations) { i ->
      val ms = timeNanoMs {
        coroutineScope {
          parallelRepos.map { (name, tag) ->
            async {
              val tmp = Files.createTempDirectory("koci-bench-par")
              val layout = Layout.create(tmp.toString()).getOrThrow()
              registry.pull(name, tag, layout).last()
              tmp.toFile().deleteRecursively()
            }
          }.awaitAll()
        }
      }
      results.add(BenchResult("parallel-conc", "${count}-packages", 0, i, ms, warmup = false))
    }
    }
  } else {
    log("Parallel pull: skipped (need 2+ repos, found ${parallelRepos.size})")
  }

  // ── Compound: discovery flow ──
  if (enabled("discovery-list")) {
    log("Discovery: list() only...")
  repeat(warmupRuns) { registry.extensions.list().collect {} }
  repeat(iterations) { i ->
    val ms = timeNanoMs { registry.extensions.list().collect {} }
    results.add(BenchResult("compound-list", "all-repos", 0, i, ms, warmup = false))
  }
  }

  if (enabled("discovery-full")) {
    log("Discovery: list() + process all...")
  repeat(warmupRuns) {
    registry.extensions.list().collect { tagsResponse ->
      val name = tagsResponse.name
      val tags = tagsResponse.tags ?: emptyList()
      if (tags.isNotEmpty()) {
        val tag = tags.last()
        val repo = registry.repo(name)
        try {
          val desc = repo.resolve(tag).getOrThrow()
          repo.manifest(desc).getOrThrow()
        } catch (_: Exception) {}
      }
    }
  }
  repeat(iterations) { i ->
    val ms = timeNanoMs {
      registry.extensions.list().collect { tagsResponse ->
        val name = tagsResponse.name
        val tags = tagsResponse.tags ?: emptyList()
        if (tags.isNotEmpty()) {
          val tag = tags.last()
          val repo = registry.repo(name)
          try {
            val desc = repo.resolve(tag).getOrThrow()
            repo.manifest(desc).getOrThrow()
          } catch (_: Exception) {}
        }
      }
    }
    results.add(BenchResult("compound-full", "all-repos", 0, i, ms, warmup = false))
  }
  }

  log("Done. ${results.size} results collected.")
  val json = Json { prettyPrint = true }
  val jsonStr = json.encodeToString(results)
  val outputPath = opts["output"]
  if (outputPath != null) {
    File(outputPath).writeText(jsonStr)
    log("Results written to $outputPath")
  } else {
    println(jsonStr)
  }
}
