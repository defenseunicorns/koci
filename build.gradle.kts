import dev.detekt.gradle.Detekt
import java.net.URI
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kover)
  alias(libs.plugins.detekt)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.spotless)
  alias(libs.plugins.jmh)
}

buildscript { dependencies { classpath(libs.kotlinx.serialization.json) } }

group = "com.defenseunicorns"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okio)
  implementation(libs.kermit)

  detektPlugins(libs.detekt.library.rules)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(kotlin("test"))
}

tasks.test {
  useJUnitPlatform()

  testLogging {
    events("passed", "skipped", "failed")
    exceptionFormat = TestExceptionFormat.FULL
    showExceptions = true
    showCauses = true
    showStackTraces = true
  }
}

spotless {
  val ktFiles = "**/*.kt"
  val ktsFiles = "**/*.kts"

  kotlin {
    target(ktFiles, ktsFiles)

    trimTrailingWhitespace()
    leadingSpacesToTabs(2)
    endWithNewline()

    // Google style is blockIndent = 2, continuationIndent = 2, manageTrailingCommas = true
    // renovate: datasource=github-tags depName=facebook/ktfmt
    ktfmt("0.58").googleStyle().configure {
      it.setMaxWidth(100)
      it.setRemoveUnusedImports(true)
    }
  }

  // Excludes kts files from license check
  format("kt-license") {
    target(ktFiles)
    licenseHeaderFile("$rootDir/linting/LICENSE_TEMPLATE", "^((package|import)\\b)").apply {
      updateYearWithLatest(true)
    }
  }
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom("$rootDir/linting/detekt-config.yml")
  baseline = file("$rootDir/linting/detekt-baseline.xml")
}

tasks.withType<Detekt> {
  reports {
    html.required.set(true)
    sarif.required.set(true)
  }
}

kover {
  reports {
    total {
      log {
        groupBy = GroupingEntityType.APPLICATION
        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
        format = "<entity> line coverage: <value>%"
        coverageUnits = CoverageUnit.LINE
      }
    }
  }
}

val releasePleaseManifest = file(".release-please-manifest.json")

version =
  Json.decodeFromString<JsonElement>(releasePleaseManifest.readText())
    .jsonObject["."]
    ?.jsonPrimitive
    ?.content!!

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  signAllPublications()

  pom {
    name = project.name
    description = "Kotlin implementation of the OCI Distribution client specification"
    url = "https://github.com/defenseunicorns/koci"

    licenses {
      license {
        name = "The Apache License, Version 2.0"
        url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
        distribution = "repo"
      }
    }

    developers { developer { name = "Defense Unicorns" } }

    scm {
      connection = "scm:git:git://github.com/defenseunicorns/koci.git"
      developerConnection = "scm:git:ssh://github.com/defenseunicorns/koci.git"
      url = "https://github.com/defenseunicorns/koci"
    }
  }
}

publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url = URI("https://maven.pkg.github.com/defenseunicorns/koci")
      credentials {
        username = System.getenv("GITHUB_ACTOR")
        password = System.getenv("GITHUB_TOKEN")
      }
    }
  }
}

tasks.register<Test>("allTests") {
  systemProperty("TESTS_WITH_EXTERNAL_SERVICES", "true")
  useJUnitPlatform()
}

jmh {
  iterations = 3 // Number of measurement iterations
  warmupIterations = 2 // Number of warmup iterations
  fork = 1 // Number of forks
  benchmarkMode = listOf("thrpt") // Throughput mode
  timeUnit = "s" // Time unit for results
}
