plugins {
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
