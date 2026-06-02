import bench.harness.BenchResult
import bench.harness.parseArgs
import bench.harness.prettyJson
import bench.harness.timeNanoMs
import com.defenseunicorns.koci.api.Descriptor
import com.defenseunicorns.koci.api.Index
import com.defenseunicorns.koci.api.Koci
import com.defenseunicorns.koci.api.Manifest
import com.defenseunicorns.koci.api.config.AuthConfig
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun log(msg: String) = System.err.println("  [current] $msg")

fun main(args: Array<String>) {
  runBlocking {
    val opts = parseArgs(args)
    val registryUrl = opts["registry"] ?: "http://127.0.0.1:5005"
    val iterations = opts["iterations"]?.toInt() ?: 10
    val warmupRuns = opts["warmup"]?.toInt() ?: 3
    val username = opts["username"]
    val password = opts["password"]
    val enabledTests = opts["tests"]?.split(",")?.toSet()
    fun enabled(test: String) = if (test == "push") enabledTests != null && test in enabledTests
      else enabledTests == null || test in enabledTests

    val results = mutableListOf<BenchResult>()
    val auth =
      if (username != null && password != null) AuthConfig.Basic(username, password)
      else AuthConfig.None

    val kociRoot = Files.createTempDirectory("koci-bench-main").toString()
    val koci = Koci(root = kociRoot)
    val registry = koci.registry(registryUrl, auth = auth)

    suspend fun freshPull(name: String, tag: String) {
      val tmp = Files.createTempDirectory("koci-bench-pull")
      val fk = Koci(root = tmp.toString())
      try {
        fk.registry(registryUrl, auth = auth).repo(name).pull(tag).last()
      } finally {
        fk.close()
        tmp.toFile().deleteRecursively()
      }
    }

    // ── Ping ──
    if (enabled("ping")) {
      log("Ping ($warmupRuns warmup + $iterations measured)...")
      repeat(warmupRuns) { registry.ping() }
      repeat(iterations) { i ->
        results.add(BenchResult("ping", "n/a", i, timeNanoMs { registry.ping() }))
      }
    }

    // ── Catalog ──
    if (enabled("catalog")) {
      log("Catalog...")
      repeat(warmupRuns) { registry.catalog().collect {} }
      repeat(iterations) { i ->
        results.add(BenchResult("catalog", "n/a", i, timeNanoMs { registry.catalog().collect {} }))
      }
    }

    // ── Discover first 3 repos → small / medium / large ──
    log("Discovering repos...")
    val rawDiscovered = mutableListOf<Pair<String, String>>()
    registry.catalog().collect { page ->
      for (repo in page) {
        val tags = repo.tags()
        if (tags.isNotEmpty()) rawDiscovered.add(repo.name to tags.last())
      }
    }

    if (rawDiscovered.isEmpty()) {
      log("No repos found, skipping remaining tests")
      koci.close()
      File(kociRoot).deleteRecursively()
      writeResults(results, opts["output"])
      return@runBlocking
    }

    val jsonDecoder = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    data class Target(val name: String, val tag: String, val label: String, val sizeBytes: Long)

    log("Computing sizes...")
    val targets = rawDiscovered.map { (name, tag) ->
      val size = try {
        val r = registry.repo(name)
        val desc = r.resolve(tag)
        val manifest = when {
          desc == null -> null
          desc.mediaType == "application/vnd.oci.image.index.v1+json" -> {
            val index = r.fetch(desc) { s -> jsonDecoder.decodeFromString<Index>(s.reader().readText()) }
            val first = index?.manifests?.firstOrNull()
            if (first != null) r.fetch(first) { s -> jsonDecoder.decodeFromString<Manifest>(s.reader().readText()) } else null
          }
          else -> r.fetch(desc) { s -> jsonDecoder.decodeFromString<Manifest>(s.reader().readText()) }
        }
        manifest?.layers?.sumOf { it.size } ?: 0L
      } catch (_: Exception) { 0L }
      val label = name
      log("  $name:$tag = ${size / 1024 / 1024}MB")
      Target(name, tag, label, size)
    }

    val resolveRepo = targets.first().name
    val resolveTag = targets.first().tag

    // ── Resolve ──
    if (enabled("resolve")) {
      log("Resolve ($resolveRepo:$resolveTag)...")
      repeat(warmupRuns) { registry.repo(resolveRepo).resolve(resolveTag) }
      repeat(iterations) { i ->
        results.add(BenchResult("resolve", "n/a", i, timeNanoMs { registry.repo(resolveRepo).resolve(resolveTag) }))
      }
    }

    // ── Tags ──
    if (enabled("tags")) {
      log("Tags ($resolveRepo)...")
      repeat(warmupRuns) { registry.repo(resolveRepo).tags() }
      repeat(iterations) { i ->
        results.add(BenchResult("tags", "n/a", i, timeNanoMs { registry.repo(resolveRepo).tags() }))
      }
    }

    // ── Pull ──
    if (enabled("pull")) {
      for (t in targets) {
        log("Pull ${t.label} (${t.name}:${t.tag})...")
        repeat(warmupRuns) { w ->
          log("  warmup ${w + 1}/$warmupRuns...")
          freshPull(t.name, t.tag)
        }
        repeat(iterations) { i ->
          log("  iter ${i + 1}/$iterations...")
          val ms = timeNanoMs { freshPull(t.name, t.tag) }
          log("  iter ${i + 1}/$iterations done: ${String.format("%.0f", ms)}ms")
          results.add(BenchResult("pull", t.label, i, ms, sizeBytes = t.sizeBytes))
        }
      }
    }

    // ── Push ──
    if (enabled("push")) {
      data class PushSize(val label: String, val bytes: Long)
      val pushSizes = listOf(
        PushSize("5mb", 5L * 1024 * 1024),
        PushSize("50mb", 50L * 1024 * 1024),
        PushSize("500mb", 500L * 1024 * 1024),
        PushSize("1000mb", 1000L * 1024 * 1024),
      )
      for (ps in pushSizes) {
        log("Push ${ps.label} ($iterations iterations)...")
        val content = ByteArray(ps.bytes.toInt()).also { java.security.SecureRandom().nextBytes(it) }
        repeat(iterations) { i ->
          log("  iter ${i + 1}/$iterations...")
          for (b in 0..7) content[b] = (i shr (b * 8)).toByte()
          val desc = Descriptor.fromInputStream(stream = content.inputStream())
          val repoName = "push-${ps.label}-${System.nanoTime()}"
          val ms = timeNanoMs {
            registry.repo(repoName).push(stream = content.inputStream(), expected = desc).collect {}
          }
          results.add(BenchResult("push", ps.label, i, ms, sizeBytes = ps.bytes))
        }
      }
    }

    koci.close()
    File(kociRoot).deleteRecursively()

    log("Done. ${results.size} results collected.")
    writeResults(results, opts["output"])
  }
  System.exit(0)
}

private fun writeResults(results: List<BenchResult>, outputPath: String?) {
  val jsonStr = prettyJson.encodeToString(results)
  if (outputPath != null) {
    File(outputPath).writeText(jsonStr)
    log("Results written to $outputPath")
  } else {
    println(jsonStr)
  }
}
