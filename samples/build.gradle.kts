plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm)
  id("com.defenseunicorns.koci.lint")
}

dependencies {
  implementation(project(":koci"))
  implementation(libs.kotlinx.coroutines.core)
}
