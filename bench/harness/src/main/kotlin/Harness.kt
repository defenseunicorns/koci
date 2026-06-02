package bench.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BenchResult(
  val operation: String,
  val sizeLabel: String,
  val iteration: Int,
  val durationMs: Double,
  val sizeBytes: Long = 0,
)

fun parseArgs(args: Array<String>): Map<String, String> {
  val map = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--registry", "-r" -> { map["registry"] = args[++i]; i++ }
      "--iterations", "-n" -> { map["iterations"] = args[++i]; i++ }
      "--warmup", "-w" -> { map["warmup"] = args[++i]; i++ }
      "--username", "-u" -> { map["username"] = args[++i]; i++ }
      "--password", "-p" -> { map["password"] = args[++i]; i++ }
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

val prettyJson: Json = Json { prettyPrint = true }
