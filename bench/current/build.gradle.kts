plugins {
  kotlin("jvm") version "2.2.10"
  kotlin("plugin.serialization") version "2.2.10"
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":bench:harness"))
  implementation(project(":koci"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  implementation("org.slf4j:slf4j-nop:2.0.16")
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
