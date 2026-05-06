plugins {
  alias(libs.plugins.jetbrains.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kover) apply false
  alias(libs.plugins.detekt) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.binary.validator) apply false
}
