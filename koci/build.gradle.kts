import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kover)
  alias(libs.plugins.binary.validator)
  id("com.defenseunicorns.koci.lint")
  id("com.defenseunicorns.koci.publishing")
}

group = "com.defenseunicorns"

kotlin {
  jvmToolchain(21)
  explicitApi()
}

dependencies {
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.ktor.content.negotiation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okio)
  implementation(libs.kermit)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.okio.fakefilesystem)
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

tasks.register<Test>("allTests") {
  systemProperty("TESTS_WITH_EXTERNAL_SERVICES", "true")
  useJUnitPlatform()
}
