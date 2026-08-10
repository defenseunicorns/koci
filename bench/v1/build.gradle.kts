plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":bench:harness"))
  implementation(libs.koci)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.slf4j.nop)
}

tasks.register<JavaExec>("bench") {
  mainClass.set("MainKt")
  classpath = sourceSets["main"].runtimeClasspath
  jvmArgs =
    listOf(
      "-XX:+UseG1GC",
      "-Xms512m",
      "-Xmx4g",
      "-XX:+AlwaysPreTouch",
    )
  standardOutput = System.out
  errorOutput = System.err
  if (project.hasProperty("benchArgs")) {
    args = (project.property("benchArgs") as String).split(" ")
  }
}
