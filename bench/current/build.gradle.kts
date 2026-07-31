plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":bench:harness"))
  implementation(project(":koci"))
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
  // Stream stderr in real-time instead of buffering
  standardOutput = System.out
  errorOutput = System.err
  // Forward CLI args: ./gradlew bench --args="--registry localhost:5005 --iterations 10"
  if (project.hasProperty("benchArgs")) {
    args = (project.property("benchArgs") as String).split(" ")
  }
}
